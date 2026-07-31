package com.honortest;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

public class MainActivity extends android.app.Activity {

    private static final String TAG = "HonorTest";

    // ===== MCF 链路层常量（来自 assets/python/mcf + wearable） =====
    private static final int FRAME_HEAD = 0xFC;
    private static final int FRAME_TAIL = 0xCF;
    private static final int PROTO_D2D = 1;
    private static final int D2D_REQ = 0;
    private static final int D2D_RSP = 1;
    private static final int D2D_ACK = 2;
    private static final int D2D_PKT_HEAD_LEN = 4;

    // UI
    private EditText etService, etWrite, etNotify;
    private Button btnScan, btnConnect, btnDisconnect;
    private ListView listDevices;
    private TextView tvLog;
    private ArrayAdapter<String> deviceAdapter;
    private final List<BluetoothDevice> deviceList = new ArrayList<>();
    private final List<String> deviceNames = new ArrayList<>();
    private int selectedDeviceIndex = -1;

    // BLE
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt gatt;
    private BluetoothDevice currentDevice;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private int attMtu = 23; // 连接后协商
    private boolean handshakeStarted = false;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())) {
                BluetoothDevice d = (BluetoothDevice) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                log("配对状态变化: " + prev + " -> " + state
                        + (d != null ? (" (" + d.getAddress() + ")") : ""));
                if (state == BluetoothDevice.BOND_BONDED) {
                    log("✅ 已配对，重新启用通知并重发 linkUp 握手 ...");
                    handshakeStarted = false;
                    if (gatt != null && notifyChar != null) {
                        gatt.setCharacteristicNotification(notifyChar, true);
                        BluetoothGattDescriptor cccd = notifyChar.getDescriptor(
                                uuid("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null) {
                            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            if (!gatt.writeDescriptor(cccd)) runLinkUp();
                        } else {
                            runLinkUp();
                        }
                    }
                }
            }
        }
    };

    // 接收侧重组缓冲
    private byte[] reasmBuf = new byte[0];
    private final Object reasmLock = new Object();

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etService = findViewById(R.id.etService);
        etWrite = findViewById(R.id.etWrite);
        etNotify = findViewById(R.id.etNotify);
        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        listDevices = findViewById(R.id.listDevices);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        listDevices.setAdapter(deviceAdapter);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            log("无法获取 BluetoothManager");
            return;
        }
        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) {
            log("设备不支持蓝牙");
            return;
        }

        btnScan.setOnClickListener(v -> startScan());
        btnConnect.setOnClickListener(v -> connectSelected());
        btnDisconnect.setOnClickListener(v -> disconnect());

        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            selectedDeviceIndex = position;
            log("已选中: " + deviceNames.get(position));
            Toast.makeText(this, "已选中，点“连接选中”", Toast.LENGTH_SHORT).show();
        });

        if (!bluetoothAdapter.isEnabled()) {
            log("请先在系统设置中打开蓝牙");
        }
        ensurePermissions();
        registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        log("就绪。点“扫描”开始。目标服务默认已填入 nRF Connect 实测 UUID。");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ============================ 权限 ============================
    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 31) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                    need.add(Manifest.permission.BLUETOOTH_SCAN);
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 1001);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            log(ok ? "权限已授予" : "部分权限未授予，扫描可能失败");
        }
    }

    // ============================ 扫描 ============================
    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            log("蓝牙未开启");
            return;
        }
        if (leScanner == null) leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            log("BLE 扫描器不可用（设备可能不支持 LE）");
            return;
        }
        deviceList.clear();
        deviceNames.clear();
        deviceAdapter.notifyDataSetChanged();
        selectedDeviceIndex = -1;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        log("开始扫描（8 秒）...");
        leScanner.startScan(null, settings, scanCallback);
        uiHandler.postDelayed(() -> {
            try { leScanner.stopScan(scanCallback); } catch (Exception ignored) {}
            log("扫描结束，共发现 " + deviceList.size() + " 个设备");
        }, 8000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = d.getName();
            String label = (name == null ? "(未知)" : name) + "  " + d.getAddress();
            if (name != null && name.toLowerCase().contains("band")) label = "★ " + label;
            // 去重
            for (BluetoothDevice e : deviceList) if (e.getAddress().equals(d.getAddress())) return;
            deviceList.add(d);
            deviceNames.add(label);
            runOnUiThread(() -> deviceAdapter.notifyDataSetChanged());
        }
    };

    // ============================ 连接 ============================
    private void connectSelected() {
        if (selectedDeviceIndex < 0 || selectedDeviceIndex >= deviceList.size()) {
            log("请先在列表中选择一个设备");
            return;
        }
        BluetoothDevice dev = deviceList.get(selectedDeviceIndex);
        log("连接 " + dev.getAddress() + " ...");
        disconnect();
        currentDevice = dev;
        gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        log("已断开");
    }

    // ============================ GATT 回调 ============================
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("✅ 已连接 (status=" + status + ")");
                int bs = g.getDevice().getBondState();
                log("配对状态: " + (bs == BluetoothDevice.BOND_BONDED ? "已配对"
                        : bs == BluetoothDevice.BOND_BONDING ? "配对中" : "未配对"));
                if (bs != BluetoothDevice.BOND_BONDED) {
                    log("数据通道可能需配对，正在发起配对（手机与手环均确认）...");
                    try { g.getDevice().createBond(); } catch (Exception e) { log("createBond 异常: " + e); }
                }
                log("请求 MTU=517 ...");
                if (!g.requestMtu(517)) log("requestMtu 调用失败");
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("❌ 连接断开");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            attMtu = mtu;
            log("MTU = " + mtu);
            log("发现服务中 ...");
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("❌ 发现服务失败 status=" + status);
                return;
            }
            UUID svcUuid = uuid(etService.getText().toString().trim());
            // 完整 GATT 拓扑（诊断：确认数据通道是不是 c5f0ad48）
            StringBuilder topo = new StringBuilder("完整 GATT 拓扑:\n");
            for (BluetoothGattService s : g.getServices()) {
                topo.append("SVC ").append(s.getUuid()).append("\n");
                for (BluetoothGattCharacteristic c : s.getCharacteristics())
                    topo.append("   C ").append(c.getUuid()).append(" props=").append(propsStr(c.getProperties())).append("\n");
            }
            log(topo.toString());

            BluetoothGattService svc = g.getService(svcUuid);
            if (svc == null) {
                log("⚠️ 未找到目标服务 " + svcUuid);
                return;
            }
            log("✅ 找到目标服务 " + svcUuid);
            // 列出该服务下全部特征及属性（避免手抄 UUID 出错）
            StringBuilder sb = new StringBuilder("该服务下特征：\n");
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                sb.append("  ").append(c.getUuid()).append(" props=").append(propsStr(c.getProperties())).append("\n");
            }
            log(sb.toString());

            UUID wUuid = uuid(etWrite.getText().toString().trim());
            UUID nUuid = uuid(etNotify.getText().toString().trim());
            writeChar = svc.getCharacteristic(wUuid);
            notifyChar = svc.getCharacteristic(nUuid);
            // 兜底：按属性自动选用，绕开手抄错误
            if (writeChar == null) {
                writeChar = findByProperty(svc, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE);
                if (writeChar != null) log("⚠️ 输入UUID未命中，已自动选用WRITE特征: " + writeChar.getUuid());
            }
            if (notifyChar == null) {
                notifyChar = findByProperty(svc, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
                if (notifyChar != null) log("⚠️ 输入UUID未命中，已自动选用NOTIFY特征: " + notifyChar.getUuid());
            }
            if (writeChar == null) { log("❌ 未找到可写特征"); return; }
            if (notifyChar == null) { log("❌ 未找到可通知特征"); return; }
            log("✅ write/notify 特征已定位");

            // 启用通知：先 setCharacteristicNotification，再写 CCCD 0x0100
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(uuid("00002902-0000-1000-8000-00805f9b34fb"));
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(cccd)) log("⚠️ writeDescriptor(CCCD) 调用失败");
            } else {
                log("⚠️ 无 CCCD 描述符，仍尝试 setCharacteristicNotification");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!handshakeStarted) {
                handshakeStarted = true;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("✅ 通知已启用，开始 linkUp 握手 ...");
                } else {
                    log("❌ CCCD 写入失败 status=" + status + "，仍尝试发送");
                }
                runLinkUp();
            } else {
                log("通知描述符写入完成(status=" + status + ")，握手已在进行");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            log("→ 写入完成 status=" + status + (c == writeChar ? " (write 特征)" : ""));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // 旧 API（Android < 13）。Android 13+ 走下方的 3 参重载
            byte[] data = c.getValue();
            handleNotify(data);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            // 新 API（Android 13+ / API 33+），必须用这个，否则通知收不到
            handleNotify(value);
        }

        private void handleNotify(byte[] data) {
            if (data == null) return;
            log("← 收到通知(" + data.length + "B): " + hex(data));
            feedReassembler(data);
        }
    };

    // ============================ linkUp 握手 ============================
    private void runLinkUp() {
        // 第 1 步：ffi _link_up
        byte[] ffi = buildLinkUpFfi();
        log("发送 linkUp 第1步 (ffi _link_up) ...");
        sendMcf(ffi);

        // 第 2 步：_link_up2，version=66560，延时 1.2s 发送
        uiHandler.postDelayed(() -> {
            byte[] l2 = buildLinkUp2();
            log("发送 linkUp 第2步 (_link_up2, version=66560) ...");
            sendMcf(l2);
            // 第 3 步：_ping 存活测试（官方 SDK 允许在 link_up 之前），用于区分
            //   “通道全死” vs “只是 link_up 需要认证”
            uiHandler.postDelayed(() -> {
                byte[] ping = buildPing();
                log("发送 Ping (_ping 0xFF) 通道存活测试 ...");
                sendMcf(ping);
                log("⏱ 全部发送完毕，等待手环响应（约 5 秒）...");
            }, 1200);
        }, 1200);
    }

    // ============================ 发送（含分片） ============================
    private void sendMcf(byte[] payload) {
        if (gatt == null || writeChar == null) { log("未连接，无法发送"); return; }
        byte[] frame = buildFrame(payload, nextFrameId());
        int chunk = Math.max(1, attMtu - 3);
        for (int off = 0; off < frame.length; off += chunk) {
            int end = Math.min(frame.length, off + chunk);
            byte[] part = new byte[end - off];
            System.arraycopy(frame, off, part, 0, part.length);
            writeChar.setValue(part);
            int props = writeChar.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
            if (!gatt.writeCharacteristic(writeChar)) {
                log("⚠️ writeCharacteristic 返回 false @off=" + off);
            }
        }
        log("帧已发出(" + frame.length + "B): " + hex(frame));
    }

    // ============================ 接收重组 ============================
    private void feedReassembler(byte[] chunk) {
        synchronized (reasmLock) {
            byte[] merged = new byte[reasmBuf.length + chunk.length];
            System.arraycopy(reasmBuf, 0, merged, 0, reasmBuf.length);
            System.arraycopy(chunk, 0, merged, reasmBuf.length, chunk.length);
            int pos = 0;
            while (merged.length - pos >= 3) {
                if ((merged[pos] & 0xFF) != FRAME_HEAD) { pos++; continue; }
                int calcLen = ((merged[pos + 1] & 0xFF) << 8) | (merged[pos + 2] & 0xFF);
                if (merged.length - pos < calcLen) break;
                byte[] frame = new byte[calcLen];
                System.arraycopy(merged, pos, frame, 0, calcLen);
                if ((frame[calcLen - 1] & 0xFF) != FRAME_TAIL) { pos++; continue; }
                int c = crc16(frame, 1, calcLen - 3);
                int recv = ((frame[calcLen - 3] & 0xFF) << 8) | (frame[calcLen - 2] & 0xFF);
                if (c != recv) { pos++; continue; }
                // 解析成功
                byte[] payload = new byte[calcLen - 3 - 5];
                System.arraycopy(frame, 5, payload, 0, payload.length);
                log("✅ 解析到完整 MCF 帧, payload(" + payload.length + "B): " + hex(payload));
                parseTransPacket(payload);
                pos += calcLen;
            }
            // 保留未消费尾部
            int remaining = merged.length - pos;
            reasmBuf = new byte[remaining];
            System.arraycopy(merged, pos, reasmBuf, 0, remaining);
        }
    }

    private void parseTransPacket(byte[] trans) {
        if (trans.length < 1) return;
        int proto = trans[0] & 0xFF;
        if (proto != PROTO_D2D) { log("  (proto=" + proto + " 非 D2D，忽略)"); return; }
        if (trans.length < 1 + D2D_PKT_HEAD_LEN) return;
        int src = trans[1] & 0xFF;
        int dst = trans[2] & 0xFF;
        int pktId = trans[3] & 0xFF;
        int attr = trans[4] & 0xFF;
        int type = (attr >> 6) & 0x07;
        log("  D2D: src=" + src + " dst=" + dst + " pktId=" + pktId + " type=" + d2dTypeName(type));
        byte[] d2dPayload = new byte[trans.length - 5];
        System.arraycopy(trans, 5, d2dPayload, 0, d2dPayload.length);
        log("  D2D.payload: " + hex(d2dPayload));
        // 尝试当作 UTF-8 文本（常是 URPC JSON 响应）
        try {
            String s = new String(d2dPayload, StandardCharsets.UTF_8);
            if (s.chars().allMatch(ch -> ch == 0 || (ch >= 0x20 && ch < 0x7f) || ch > 0x7f)) {
                log("  D2D.payload(ascii): " + s);
            }
        } catch (Exception ignored) {}
        if (type == D2D_ACK) log("  => 收到 ACK（对端确认）");
        if (type == D2D_RSP) log("  => 收到 RSP（对端响应，握手/调用成功）");
    }

    // ============================ 协议构造（移植自 honor-ble.js） ============================
    private static int frameIdCounter = 0;
    private int nextFrameId() { frameIdCounter = (frameIdCounter + 1) & 0xFF; return frameIdCounter; }

    // CRC16/MODBUS
    private static int crc16(byte[] buf, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (buf[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 1) != 0) ? (crc >> 1) ^ 0xA001 : (crc >> 1);
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] buildFrame(byte[] payload, int frameId) {
        int headLen = 5;
        int tailLen = 3; // CF + CRC16(2)
        int frameLen = headLen + tailLen + payload.length;
        byte[] f = new byte[frameLen];
        int p = 0;
        f[p++] = (byte) FRAME_HEAD;
        f[p++] = (byte) ((frameLen >> 8) & 0xFF);
        f[p++] = (byte) (frameLen & 0xFF);
        f[p++] = (byte) (frameId & 0xFF);
        f[p++] = 0; // flag
        System.arraycopy(payload, 0, f, p, payload.length); p += payload.length;
        int c = crc16(f, 1, p - 1); // 范围：去帧头，到 payload 结束
        f[p++] = (byte) ((c >> 8) & 0xFF);
        f[p++] = (byte) (c & 0xFF);
        f[p++] = (byte) FRAME_TAIL;
        return f;
    }

    private static byte[] buildTransPacket(int proto, byte[] payload) {
        byte[] t = new byte[1 + payload.length];
        t[0] = (byte) (proto & 0xFF);
        System.arraycopy(payload, 0, t, 1, payload.length);
        return t;
    }

    private static byte[] buildD2DPacket(int srcId, int dstId, int pktId, int type,
                                         boolean needAck, boolean needRsp, byte[] payload) {
        byte[] raw = new byte[D2D_PKT_HEAD_LEN + payload.length];
        raw[0] = (byte) (srcId & 0xFF);
        raw[1] = (byte) (dstId & 0xFF);
        raw[2] = (byte) (pktId & 0xFF);
        raw[3] = (byte) (((type & 0x07) << 6) | ((needAck ? 1 : 0) << 5)
                | ((needRsp ? 1 : 0) << 4) | ((0 & 0x03) << 2) | (0 & 0x03));
        System.arraycopy(payload, 0, raw, D2D_PKT_HEAD_LEN, payload.length);
        return raw;
    }

    private static byte[] buildServicePayload(String name, int ver, byte[] input) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[nameBytes.length + 1 + 1 + input.length];
        System.arraycopy(nameBytes, 0, p, 0, nameBytes.length);
        p[nameBytes.length] = 0;
        p[nameBytes.length + 1] = (byte) (ver & 0xFF);
        System.arraycopy(input, 0, p, nameBytes.length + 2, input.length);
        return p;
    }

    // linkUp 第1步：ffi _link_up
    private static byte[] buildLinkUpFfi() {
        byte[] name = "_link_up".getBytes(StandardCharsets.UTF_8);
        byte[] ffiInput = new byte[1 + 2 + name.length];
        ffiInput[0] = (byte) 0x81; // U8|ARRAY 类型标签
        ffiInput[1] = (byte) (name.length & 0xFF);
        ffiInput[2] = 0;
        System.arraycopy(name, 0, ffiInput, 3, name.length);
        byte[] svc = buildServicePayload("ffi", 1, ffiInput);
        byte[] d2d = buildD2DPacket(0, 1, 0, D2D_REQ, false, true, svc);
        byte[] trans = buildTransPacket(PROTO_D2D, d2d);
        return trans; // 外层帧在 sendMcf 中打包
    }

    // linkUp 第2步：_link_up2, version=66560
    private static byte[] buildLinkUp2() {
        byte[] input = "{\"version\":66560}".getBytes(StandardCharsets.UTF_8);
        byte[] svc = buildServicePayload("_link_up2", 1, input);
        byte[] d2d = buildD2DPacket(0, 1, 0, D2D_REQ, false, true, svc);
        byte[] trans = buildTransPacket(PROTO_D2D, d2d);
        return trans;
    }

    // 通道存活测试：_ping，参数 U8|ARRAY "_ping" + U8 0xFF（对齐官方 SDK HEARTBEAT_SERVICE）
    private static byte[] buildPing() {
        byte[] arg1 = new byte[]{ (byte) 0x81, 0x05, 0x00, 0x5F, 0x70, 0x69, 0x6E, 0x67 }; // U8|ARRAY "_ping"
        byte[] arg2 = new byte[]{ 0x01, (byte) 0xFF };                                    // U8 0xFF
        byte[] input = new byte[arg1.length + arg2.length];
        System.arraycopy(arg1, 0, input, 0, arg1.length);
        System.arraycopy(arg2, 0, input, arg1.length, arg2.length);
        byte[] svc = buildServicePayload("_ping", 1, input);
        byte[] d2d = buildD2DPacket(0, 1, 0, D2D_REQ, false, true, svc);
        return buildTransPacket(PROTO_D2D, d2d);
    }

    // ============================ 工具 ============================
    private static UUID uuid(String s) { return UUID.fromString(s.trim()); }

    private static BluetoothGattCharacteristic findByProperty(BluetoothGattService svc, int wanted) {
        BluetoothGattCharacteristic fallback = null;
        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
            int p = c.getProperties();
            if ((p & wanted) != 0) {
                // 优先选同时带 NOTIFY 的之外；这里简单取第一个匹配
                if (fallback == null) fallback = c;
                if ((wanted & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        && (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) return c;
            }
        }
        return fallback;
    }

    private static String propsStr(int p) {
        StringBuilder s = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) s.append("R");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) s.append("W");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) s.append("WNR");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) s.append("N");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) s.append("I");
        if (s.length() == 0) s.append("?");
        return s.toString();
    }

    private static String hex(byte[] b) { return hex(b, 0, b.length); }
    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
            if ((i - off) % 1 == 0 && i < off + len - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private static String d2dTypeName(int t) {
        switch (t) {
            case D2D_REQ: return "REQ";
            case D2D_RSP: return "RSP";
            case D2D_ACK: return "ACK";
            default: return "type" + t;
        }
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> {
            tvLog.append(s + "\n");
            // 自动滚动到底部
            final int scrollAmount = tvLog.getLayout() != null
                    ? tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight()
                    : 0;
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount);
        });
    }
}
