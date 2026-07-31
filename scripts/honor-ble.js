/*
 * honor-ble.js — 荣耀手环直连协议栈（MCF / Persimwear 逆向还原版）
 *
 * 协议分层（自底向上）：
 *   1. BLE GATT 传输     ：帧按 ATT MTU 分片写入 write 特征，notify 特征回传
 *   2. MCF 链路帧        ：FC + lenH + lenL + frameId + flag + payload + CRC16 + CF
 *   3. 传输包 TransPacket ：[proto(1)=D2D] + 负载
 *   4. D2D 包            ：[src_id, dst_id, pkt_id, attr, payload...]
 *   5. 应用层命令        ：D2D.payload 的具体编解码（由各 wearable 业务模块定义，见下方 TODO）
 *
 * 逆向来源：com.hihonor.health v17.22.1.301 内嵌 Python SDK (assets/python/mcf/*)
 *
 * ⚠️ 唯一待补项：BLE GATT 的 service / write / notify 三个 UUID。
 *    请用 nRF Connect 连手环后填入下方 TODO。其余协议已全部实现。
 */

// ===================== GATT UUID（由 nRF Connect 实测）=====================
// 候选：HONOR Band 9 的 MCF 数据通道，c5f0ad48... 服务带独立的写/通知特征。
const GATT_SERVICE_UUID   = "c5f0ad48-cc27-48fd-9f67-8dc8e8f4a9cb";
const GATT_WRITE_UUID     = "2a5fc8bd-3de4-43cb-9449-354bcdeccc70";
const GATT_NOTIFY_UUID    = "a563d757-b216-48bc-8a0f-e8dde1f916aa";
// 备选（若上面连不上可试）：
// svc=0x3802        char=0x4A02
// svc=cc353442-...  char=c551c36a-...
// ========================================================================

// 链路层常量（来自 mcf/link + wearable/__init__.py）
const FRAME_HEAD = 0xFC;
const FRAME_TAIL = 0xCF;
const BLE_GATT_MTU_SIZE = 517;            // 协商后的 ATT MTU（实际用设备返回值）
const BLE_GATT_DATA_FRAME_SIZE = 512;     // 单帧 payload 上限 = MTU - 3 - 2

// 传输层 proto
const ProtoType = { D2D: 1, ARP: 2, USER: 3 };

// D2D 包类型
const D2DType = { REQ: 0, RSP: 1, ACK: 2, BROADCAST: 3, PROXY: 4 };
const D2D_DEV_ID_BROADCAST = 255;
const D2D_PKT_HEAD_LEN = 4;

const MCF_ACK_TIMEOUT = 500;   // ms，对应 MCF_ACK_TIMOUT=0.5s
const MCF_REQ_RETRY = 2;

// ============================ CRC16 (MODBUS) ============================
// poly 0x8005 (reflected 0xA001), init 0xFFFF, refin/refout=true
// 与 mcf/mcf_utils.py 的 crc16(frame[1:]) 行为一致（已用样例交叉验证）
function crc16(buf) {
  let crc = 0xFFFF;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let b = 0; b < 8; b++) {
      crc = (crc & 1) ? (crc >> 1) ^ 0xA001 : (crc >> 1);
    }
  }
  return crc & 0xFFFF;
}

// ====================== MCF 链路帧 封装/解析 ======================
// 单帧：FC | lenH | lenL | frameId | flag(0) | payload | [CRC16(2)] | CF
// len = 整帧字节数（大端）
function buildFrame(payload, frameId, useCrc = true) {
  const headLen = 5;
  const tailLen = useCrc ? 1 + 2 : 1; // CF + (CRC16)
  const frameLen = headLen + tailLen + payload.length;
  const f = new Uint8Array(frameLen);
  let p = 0;
  f[p++] = FRAME_HEAD;
  f[p++] = (frameLen >> 8) & 0xFF;
  f[p++] = frameLen & 0xFF;
  f[p++] = frameId & 0xFF;
  f[p++] = 0; // flag
  f.set(payload, p); p += payload.length;
  if (useCrc) {
    const c = crc16(f.subarray(1, p)); // CRC 范围：去帧头，到 payload 结束（不含尚未写入的 CRC/帧尾）
    f[p++] = (c >> 8) & 0xFF;
    f[p++] = c & 0xFF;
  }
  f[p++] = FRAME_TAIL;
  return f;
}

// 接收侧重组器（移植自 mcf/link/char_dev.py frame_check_main）
class FrameReassembler {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this.buf = new Uint8Array(0);
    this.calcLen = 0;
  }
  // 把一端 ATT 分片数据喂入
  input(chunk) {
    const merged = new Uint8Array(this.buf.length + chunk.length);
    merged.set(this.buf, 0);
    merged.set(chunk, this.buf.length);
    this.buf = merged;
    this._scan();
  }
  _scan() {
    while (this.buf.length >= 3) {
      if (this.buf[0] !== FRAME_HEAD) { this.buf = this.buf.subarray(1); continue; }
      const calcLen = (this.buf[1] << 8) | this.buf[2];
      if (this.buf.length < calcLen) break; // 帧未收全
      const frame = this.buf.subarray(0, calcLen);
      // 校验帧尾 + CRC
      if (frame[calcLen - 1] !== FRAME_TAIL) { this.buf = this.buf.subarray(1); continue; }
      const c = crc16(frame.subarray(1, calcLen - 3));
      const recvC = (frame[calcLen - 3] << 8) | frame[calcLen - 2];
      if (c !== recvC) { this.buf = this.buf.subarray(1); continue; }
      // 取出 payload（去 5 字节头 + 2 字节 CRC + 1 字节尾）
      const payload = frame.subarray(5, calcLen - 3);
      this.onFrame(payload);
      this.buf = this.buf.subarray(calcLen);
    }
  }
}

// ====================== 传输包 / D2D 包 ======================
function buildTransPacket(proto, payload) {
  const t = new Uint8Array(1 + payload.length);
  t[0] = proto & 0xFF;
  t.set(payload, 1);
  return t;
}

// D2D 包：src_id, dst_id, pkt_id, attr, payload
function buildD2DPacket({ srcId, dstId, pktId, type, needAck = false, needRsp = false, priority = 0, reserve = 0, payload = new Uint8Array(0) }) {
  const raw = new Uint8Array(D2D_PKT_HEAD_LEN + payload.length);
  raw[0] = srcId & 0xFF;
  raw[1] = dstId & 0xFF;
  raw[2] = pktId & 0xFF;
  raw[3] = ((type & 0x07) << 6) | ((needAck ? 1 : 0) << 5) | ((needRsp ? 1 : 0) << 4) | ((priority & 0x03) << 2) | (reserve & 0x03);
  raw.set(payload, D2D_PKT_HEAD_LEN);
  return raw;
}

function parseD2DPacket(raw) {
  return {
    srcId: raw[0],
    dstId: raw[1],
    pktId: raw[2],
    type: (raw[3] >> 6) & 0x07,
    needAck: !!((raw[3] >> 5) & 0x01),
    needRsp: !!((raw[3] >> 4) & 0x01),
    priority: (raw[3] >> 2) & 0x03,
    reserve: raw[3] & 0x03,
    payload: raw.subarray(D2D_PKT_HEAD_LEN)
  };
}

// ====================== 主控制器 ======================
const HonorBLE = {
  deviceId: null,
  attMtu: BLE_GATT_MTU_SIZE,
  _frameId: 0,
  _pktId: 0,
  _reassembler: null,
  _pending: new Map(),       // pktId -> {resolve, timer}
  _dataHandlers: [],         // 应用层数据回调

  // ---- 连接 ----
  async connect() {
    if (GATT_SERVICE_UUID.startsWith("TODO")) {
      throw new Error("请先在文件顶部填入 nRF Connect 测得的 3 个 GATT UUID");
    }
    const BleClient = (await import("@capacitor-community/bluetooth-le")).BleClient;
    await BleClient.initialize();
    const device = await BleClient.requestDevice({ services: [GATT_SERVICE_UUID] });
    this.deviceId = device.deviceId;
    await BleClient.connect(this.deviceId);
    // 读取协商 MTU（有的封装返回；没有则用默认 517-3）
    try { this.attMtu = await BleClient.getMtu(this.deviceId); } catch (e) { this.attMtu = BLE_GATT_MTU_SIZE - 3; }

    this._reassembler = new FrameReassembler((payload) => this._onLinkPayload(payload));
    await BleClient.startNotifications(this.deviceId, GATT_SERVICE_UUID, GATT_NOTIFY_UUID, (evt) => {
      // evt.value 为 DataView/ArrayBuffer
      const dv = evt.value instanceof DataView ? evt.value : new DataView(evt.value);
      this._reassembler.input(new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength));
    });
    return this.deviceId;
  },

  async disconnect() {
    const { BleClient } = await import("@capacitor-community/bluetooth-le");
    if (this.deviceId) await BleClient.disconnect(this.deviceId);
    this.deviceId = null;
  },

  // ---- 链路层发送（含按 MTU 分片） ----
  async _sendFrame(frame) {
    const { BleClient } = await import("@capacitor-community/bluetooth-le");
    const chunkSize = this.attMtu - 3; // ATT 写最大值
    for (let off = 0; off < frame.length; off += chunkSize) {
      const chunk = frame.subarray(off, off + chunkSize);
      const dv = new DataView(chunk.buffer, chunk.byteOffset, chunk.byteLength);
      await BleClient.write(this.deviceId, GATT_SERVICE_UUID, GATT_WRITE_UUID, dv);
    }
  },

  _nextFrameId() { this._frameId = (this._frameId + 1) & 0xFF; return this._frameId; },
  _nextPktId() { this._pktId = (this._pktId + 1) & 0xFF; return this._pktId; },

  // ---- 收到完整链路 payload：解开 TransPacket -> D2D -> 分发 ----
  _onLinkPayload(transPayload) {
    const proto = transPayload[0];
    const d2dRaw = transPayload.subarray(1);
    if (proto !== ProtoType.D2D) { /* ARP/USER 暂忽略 */ return; }
    const pkt = parseD2DPacket(d2dRaw);

    if (pkt.type === D2DType.ACK) {
      const p = this._pending.get(pkt.pktId);
      if (p) { clearTimeout(p.timer); this._pending.delete(pkt.pktId); }
      return;
    }
    if (pkt.type === D2DType.RSP) {
      const p = this._pending.get(pkt.pktId);
      if (p) { clearTimeout(p.timer); p.resolve(pkt.payload); this._pending.delete(pkt.pktId); }
      return;
    }
    if (pkt.type === D2DType.REQ) {
      // 收到对端请求：自动回 ACK，再交给应用层处理
      if (pkt.needAck) this._sendAck(pkt);
      this._dataHandlers.forEach((h) => h(pkt));
      return;
    }
  },

  _sendAck(pkt) {
    const ack = buildD2DPacket({ srcId: pkt.dstId, dstId: pkt.srcId, pktId: pkt.pktId, type: D2DType.ACK });
    const trans = buildTransPacket(ProtoType.D2D, ack);
    this._sendFrame(buildFrame(trans, this._nextFrameId()));
  },

  // ---- 应用层：发送一个请求并等待 RSP ----
  // payload = 具体业务命令字节（由各 wearable 模块定义，见下方 TODO）
  async request(dstId, payload, { needAck = true, needRsp = true, timeout = 2000 } = {}) {
    if (!this.deviceId) throw new Error("未连接");
    const pktId = this._nextPktId();
    const d2d = buildD2DPacket({
      srcId: 0, dstId, pktId, type: D2DType.REQ,
      needAck, needRsp, payload: payload instanceof Uint8Array ? payload : new Uint8Array(payload)
    });
    const trans = buildTransPacket(ProtoType.D2D, d2d);
    const frame = buildFrame(trans, this._nextFrameId());

    return new Promise((resolve, reject) => {
      let timer = null;
      if (needRsp) {
        timer = setTimeout(() => { this._pending.delete(pktId); reject(new Error("D2D RSP 超时")); }, timeout);
        this._pending.set(pktId, { resolve, timer });
      }
      this._sendFrame(frame).catch(reject);
    });
  },

  // 注册应用层数据回调（收到对端 REQ 时触发）
  onRequest(handler) { this._dataHandlers.push(handler); },

  // ============================ URPC 应用层（Persimwear） ============================
  // 构造 URPC 服务请求 payload：name(utf8) + 0x00 + ver(1B) + input
  _buildServicePayload(name, ver, inputBytes) {
    const nameBytes = new TextEncoder().encode(name);
    const p = new Uint8Array(nameBytes.length + 1 + 1 + inputBytes.length);
    p.set(nameBytes, 0); p[nameBytes.length] = 0; p[nameBytes.length + 1] = ver & 0xFF;
    p.set(inputBytes, nameBytes.length + 2);
    return p;
  },

  // 内部调用服务（dst_id=1 daemon），返回 Promise<Uint8Array>
  async _callServiceInternal(name, inputBytes, ver = 1) {
    const payload = this._buildServicePayload(name, ver, inputBytes);
    return this.request(1, payload, { needAck: false, needRsp: true, timeout: 5000 });
  },

  // 公开：调用任意 URPC 服务。input 可以是对象（自动 JSON）或 Uint8Array
  async callService(name, input, ver = 1) {
    let inputBytes;
    if (input instanceof Uint8Array) inputBytes = input;
    else inputBytes = new TextEncoder().encode(JSON.stringify(input || {}));
    const rsp = await this._callServiceInternal(name, inputBytes, ver);
    return new TextDecoder().decode(rsp);
  },

  // 链路建立握手（连接成功后必须先调用）
  async linkUp() {
    // 1) FFI 方式调用 _link_up
    const linkUpName = new TextEncoder().encode("_link_up");
    const ffiInput = new Uint8Array(1 + 2 + linkUpName.length);
    ffiInput[0] = 0x81; // U8|ARRAY
    ffiInput[1] = linkUpName.length; ffiInput[2] = 0;
    ffiInput.set(linkUpName, 3);
    await this._callServiceInternal("ffi", ffiInput, 1);

    // 2) 普通服务方式调用 _link_up2，带上版本号 0x10400 = 66560
    await this.callService("_link_up2", { version: 66560 }, 1);
  },

  // TSDB 查询（路径格式待确认；常见如 "/data/sport_step" 等需进一步抓包确定）
  async queryTsdb(path, index = 0, count = -1) {
    return this.callService("tsdb_query", { path, index, count });
  }
};

/*
 * ============================ 后续 TODO ============================
 * 1. 把 honor-ble.js 放到 mobile/www/js/，在 app.js 中 import { HonorBLE } 使用。
 * 2. 先在真机测试 `await HonorBLE.connect(); await HonorBLE.linkUp();` 是否成功。
 * 3. TSDB 健康数据路径格式未知，需从荣耀运动健康 App 运行时抓包或继续逆向 dex。
 *    可尝试路径如："/data/sport_step"、"/data/heart_rate"、"/data/sleep" 等，
 *    或对比官方 App 抓到的实际 tsdb_query 请求。
 */

if (typeof module !== "undefined") module.exports = { HonorBLE, crc16, buildFrame, buildTransPacket, buildD2DPacket, parseD2DPacket, FrameReassembler };
