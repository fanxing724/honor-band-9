# 荣耀手环数据获取项目 — AI 接力交接文档

> 本文档面向**接力的 AI**。阅读后可直接接手本项目，无需追问背景。
> 最后更新：**2026-07-30 深夜**（本轮重大进展：协议已逆向、卡在 X25519 认证）

---

## 〇、最重要约束（决定整个技术路线）

1. **用户手机只能运行 `.apk` 文件**，不能跑网页 / WebView / 开发环境。
   → 因此**所有可验证的成果必须是能安装运行的 Android APK**，不是 Web/Capacitor 页面。
   → 已据此把"测试 App"做成**纯原生 Android（Java，零第三方依赖）**，手动流水线构建（见第六节）。
2. 用户要的是**自己的独立 App 管理手环**（基于荣耀运动健康 App 的手环功能板块），**不依赖官方 App 常驻**、不等荣耀开发者账号审核。
3. 仅用于读取**用户自有设备**数据，个人用途。

---

## 一、项目目标

从 `com.hihonor.health`（荣耀运动健康 App）逆向出它与**荣耀手环**通信的私有 BLE 协议栈与认证，做出一个**能直接连手环、读全部健康数据（步数/心率/血氧/压力/睡眠）的独立 Android App**。

根本诉求：**不依赖荣耀官方开发者账号审核**，不等 OAuth 账号下来。

---

## 二、已彻底纠正的早期误判（务必先看，避免走回头路）

| 早期误判（旧文档） | 真实情况（本轮已验证） |
|---------------------|------------------------|
| 私有协议是 LPv2 / LPv3 | ❌ 错。`lpv2`/`lpv3` 是混淆后的**随机类名**（如 `Lpv3` 是 TTS 包装类），**不是协议版本**。真实协议是 **RT-Thread Persimwear 框架**的 **MCF** 链路层，源码以**内嵌 Python** 形式打包在 APK 的 `assets/python/` 中。 |
| 密钥在 `libwhiteBoxJniLib.so` | ❌ 错。真实文件名是 **`libwhitebox.so`**（小写 box）。认证核心在 **`libmbblink.so`**（X25519 + AES）。 |
| 走 Web/Capacitor 页面 | ❌ 不可行。用户手机只能跑 apk（见第〇节）。实际载体是原生 `testapp/`。 |
| 华为私有协议 0xfee0 | ⚠️ 旧线索。真机实测 HONOR Band 9 的 MCF 数据通道服务是 **`c5f0ad48-...`**（见第四节），不是 0xfee0。 |

---

## 三、已下载与已逆向的资产

### 3.1 源 APK
- 路径：`/mnt/workspace/reverse-honor/honor-health.apk`（242MB）
- 包名 `com.hihonor.health`，版本 **v17.22.1.301**
- 来源：腾讯 CDN（本环境可访问），非 GitHub（GitHub 被墙，jadx 装不了）

### 3.2 内嵌 Python SDK（协议真相在此，已读关键文件）
路径：`/mnt/workspace/reverse-honor/extracted/py/assets/python/`
- `mcf/link/char_dev.py` —— MCF 帧收发包/重组/CRC（帧格式与 CRC 范围）。
- `mcf/link/link.py` —— MCF 链路层常量与帧封装。
- `mcf/trans/d2d.py` —— D2D 包定义、`pkt_gen`、ACK/RSP 处理（确认 `daemon_id=1`、attr 编码）。
- `mcf/trans/trans.py` —— 传输层（无额外握手，link_up 即链路建立）。
- `urpc/src/urpc.py` —— **URPC 应用层**：`exec_link_up()` = `ffi "_link_up"` + `_link_up2`；`__gen_pkt` 格式；`daemon_id=1`。
- `urpc/src/ffi.py` —— **`Arg` 类型编码**：`U8=0x01, ARRAY=0x80`，`U8|ARRAY=0x81`，长度 **小端 2 字节**。
- `urpc/src/urpc_utils.py` —— 异常类。

### 3.3 Native 认证库（已提取，本轮新发现）
路径：`/mnt/workspace/reverse-honor/native/lib/<arch>/`
- **`libmbblink.so`** —— 导出：
  - `GenerateX25519KeyPair`、`GetX25519SharedKey`
  - `X25519`、`X25519_keypair`、`X25519_public_from_private`、`x25519_ge_scalarmult*` 等
  - `aes_key_schedule` / `aes_setkey_enc` / `aes_crypt_ecb`
  - `wb_aes_decrypt*`、`wb_aes_get_custom_data_d`（白盒 AES）
  → **结论：认证是本地 X25519 ECDH + AES（可能白盒），不是必须云端账号**。可独立实现。
- `libwhitebox.so` —— 白盒解密支撑库。

### 3.4 dex（已提取，待深挖握手编排）
路径：`/mnt/workspace/reverse-honor/dexout/*.dex`（classes.dex … classes27.dex）
grep 命中关键类（**下一步要逆向的握手编排**）：
- `com.hihonor.deviceservice.impl.link.mbb.MbbLink`（及 `MbbLink.kt`）
- `com.hihonor.hwddmp.connection.bluetooth.mbb.MbbLinkProtoV2Impl`（及 `MbbLinkProtoArgv`、`MbbLinkProtoManager`）
- `com.hihonor.mbblink.host.*`（Wrapper / Connection / Device / loader）
- `com.hihonor.securityengine.keymanager.sdk.HnKeyManager$AlgX25519` 与 `cryption/algorithm/AlgX25519`
- `com.hihonor.deviceauthengine`（HiChain AIDL，可能是编排者，需确认是否必须）
- 关键字符串：`start generate X25519 keypair`、`generateX25519KeyPair`、`getX25519SharedKey`、`handleAuthTypeResponse: Create X25519 key Pair failed.`、`EXTRA_MAGIC_RING`、`TAG_HD_DEVICE_SERVICE_API_MbbLink`

---

## 四、已确认的 GATT UUID（HONOR Band 9-EF1 真机实测）

| 角色 | UUID | 属性（真机） |
|------|------|--------------|
| 数据通道服务 | `c5f0ad48-cc27-48fd-9f67-8dc8e8f9b34fb` | — |
| 写特征 | `2a5fc8bd-3de4-43cb-9449-354bcdeccc70` | **WWNR**（WRITE + WRITE_NO_RESPONSE） |
| 通知特征 | `a563d757-b216-48bc-8a0f-e8dde1f916aa` | **N**（NOTIFY） |

> 写 UUID 末段曾误写为 `354bcdecccc70`（多 2 字符，非法 14 位），正确为 `354bcdeccc70`（12 位）。已修正并在 testapp/ 与 honor-ble.js 同步。

**真机完整 GATT 拓扑**（其余服务，暂非目标）：
- 标准：`00001800`(GAP) / `00001801`(GATT) / `0000180a`(Device Info) / `00001812`（多功能，多个 WNR/RN 特征）
- 候选：`00003802`（`4a02` RwWNRN）、`cc353442-be58-4ea2-876e-11d8d6976366`（`c551c36a-...` RwWNRN）

---

## 五、已验证的协议栈（移植到 Java 并已单测/真机验证）

### 5.1 帧格式（MCF）
```
FC | lenH(1) | lenL(1) | frameId(1) | flag(1)=0 | payload(...) | CRC16(2) | CF
len = 整帧字节数（大端）；CRC16 范围 = frame[1 .. 末尾前3字节]（去帧头，不含 CRC/尾）
```
- **CRC16 = MODBUS**：poly `0x8005`（反射 `0xA001`），init `0xFFFF`，refin/refout=true。
- 已交叉验证：`crc16([0,1,2,3,4,5]) = 0xa00e`（Java 版与 Python `mcf.mcf_utils.crc16` 一致）。

### 5.2 分层封装（→ 发送前用 buildFrame 包最外层 MCF 帧）
1. **TransPacket**：`[proto(1)=1 D2D] + payload`
2. **D2D 包**：`[src(1), dst(1), pktId(1), attr(1), payload...]`
   - `attr = (type<<6) | (needAck<<5) | (needRsp<<4) | (priority<<2) | reserve`
   - 类型：REQ=0, RSP=1, ACK=2
   - 目标 daemon：`dst_id = 1`
3. **URPC service 负载**：`name(utf8) + 0x00 + ver(1) + input`
4. **linkUp 两步**（已发送到真机、字节正确）：
   - 第1步 `ffi "_link_up"`：`input = [0x81, len(LE16), "_link_up"]`（U8|ARRAY 编码）
   - 第2步 `_link_up2`：`input = JSON '{"version":66560}'`（即 `0x10400`）

### 5.3 参考实现
- `/mnt/workspace/reverse-honor/honor-ble.js` —— Web/Capacitor 版协议栈（单测通过，因手机不能跑网页仅作参考）。
- 真机验证用的 Java 版在 `testapp/`（见第六节），CRC16/帧/D2D/URPC 逻辑与上面一致。

---

## 六、测试 App（真机验证载体，已构建可安装）

路径：`/mnt/workspace/reverse-honor/testapp/`
产出 APK：**`/mnt/workspace/reverse-honor/testapp/honor-band-test.apk`**（已签名 debug）

### 功能
扫描 BLE → 列表选设备 → 连接 → 请求 MTU(517) → 发现服务 → 启用 notify(写 CCCD 0x0100) → 自动 `linkUp` 两步 + `_ping` 存活测试 → 屏幕实时日志（含**完整 GATT 拓扑**、发出的帧 hex、收到的 notify 与 MCF 帧解析）。

### 已修过的坑（重要，别再踩）
1. **UUID 输入框锁定**：用户会误触改错 UUID，已设为 `focusable=false`（只读）。
2. **Android 13+ 回调**：`onCharacteristicChanged` 2 参版被弃用，必须覆写 **3 参版** `onCharacteristicChanged(gatt, characteristic, value)`，否则通知被静默丢弃（表现正是"写入成功但零回包"）。
3. **自动配对**：连接后若未配对则 `createBond()`，配对成功(10→11→12)后重发握手。
4. **强制 WRITE_NO_RESPONSE**：写特征属性为 WWNR，按官方用法用无响应写。

### 构建方式（无 Gradle，因 maven.google.com 被墙）
```
JDK17 (apt install openjdk-17-jdk-headless)
sdkmanager 装 build-tools;34.0.0 + platforms;android-34（dl.google.com 可达）
javac → d8 --min-api 21 --lib android.jar → aapt2 compile/link --auto-add-overlay
  → zip 塞 classes.dex → zipalign → apksigner（debug.keystore 已生成）
构建脚本：testapp/build.sh
```

---

## 七、当前阻塞点（核心未解）

**现象（真机日志实证）**：linkUp 两步 + `_ping` 帧均**字节正确、写入 `status=0`**，但**手环任何 notify 回包都没有**（连最简单的 `_ping` 都静默）。即便 BLE 绑定走完（配对状态 10→11→12）依旧。

**结论**：裸 BLE 绑定**不够**。手环要**荣耀应用层认证（X25519 / HiChain）**走完，才会处理任何 MCF 帧。我们的帧是对的，只差"被认证"。

**利好**：`libmbblink.so` 证明认证是**本地 X25519 ECDH + AES（白盒）**，并非必须云端账号 → 有希望在独立 App 内完整实现。

---

## 八、下一步任务清单（按优先级，明天接力执行）

### 任务 A（最高优先）：逆向 X25519 握手编排
目标：从 dex 搞清**手环与 App 之间到底交换了哪些 BLE 消息来完成认证**，以及**共享密钥如何变成 MCF 会话的 AES 密钥/IV/模式**。
- 用 `androguard` 或 `strings`/反编译分析以下类，画出握手时序：
  - `com.hihonor.hwddmp.connection.bluetooth.mbb.MbbLinkProtoV2Impl`
  - `com.hihonor.deviceservice.impl.link.mbb.MbbLink`
  - `com.hihonor.securityengine.keymanager.sdk.HnKeyManager$AlgX25519` / `AlgX25519`
  - `com.hihonor.mbblink.host.*`（Wrapper/Connection/Device）
- 重点回答：
  1. 公钥在哪条 BLE 写/通知里交换？用的哪个服务/特征（是不是 `c5f0ad48` 通道，还是另一条）？
  2. `GetX25519SharedKey` 产出的共享密钥，如何派生 AES 密钥（KDF？直接截断？salt？）+ IV + 模式（ECB/CBC/CTR？白盒还是普通 AES？）
  3. 认证后的 MCF 帧是否整体加密？加密范围（整 payload？仅数据？）+ 是否带额外 MAC/nonce？
  4. `com.hihonor.deviceauthengine`(HiChain) 是不是必须的？还是仅本地 ECDH 即可？
- 参考字符串：`handleAuthTypeResponse`、`start generate X25519 keypair`、`EXTRA_MAGIC_RING`、`generateX25519KeyPair`、`getX25519SharedKey`。

### 任务 B：把认证移植进 testapp（拿到 A 的答案后）
- 优先用 **Android 自带 X25519**（Conscrypt，`X25519PrivateKeyParameters` 等，API 28+ 可用），避免搬混淆的 native so。
- 实现握手后，在 `testapp` 里：连接 → 交换公钥 → 派生会话密钥 → 发送加密的 linkUp → 读 notify。

### 任务 C（可选诊断，用户侧可做）：官方 App 配对实验
让用户先用**官方「荣耀运动健康」App 把手环配对一次**（完成 HiChain 认证），然后跑我们的 testapp。若此时 `_ping` 有回包 → 证明"帧正确、只差认证"，大幅降低 A 的难度预期。若仍静默 → 认证绑定在官方 App 的实时会话/账号，需重新审视。

### 任务 D（兜底，独立但不同路线）：荣耀 Health Kit OAuth
- `honor-health.js` 已实现完整 OAuth2 + 数据查询客户端（方案 2），只等用户在 developer.hihonor.com 拿到 `appId/appSecret`。
- 与 BLE 路线互补：BLE 直连读实时/全量；OAuth 走云端。但本会话主线是 BLE 直连。

---

## 九、环境速查

| 项 | 状态 |
|----|------|
| 本机 | PAI-DSW（CPU，无 GPU，无外网 GitHub） |
| GitHub / jadx | 被墙，不可用 |
| pypi / androguard / dl.google.com | 可达 |
| JDK | apt 装 `openjdk-17-jdk-headless` 成功 |
| Android SDK | sdkmanager 装 `build-tools;34.0.0` + `platforms;android-34` 成功（dl.google.com 可达） |
| maven.google.com（Gradle 依赖） | 被墙 → 不能走 Gradle，改手动 javac+d8+aapt2 流水线 |
| 用户设备 | HONOR Band 9-EF1，MAC `34:86:DA:31:DE:F1`；手机型号/Android 版本未知（但已验证支持 Android 13+ 3参回调路径） |

---

## 十、给接力 AI 的一句话

**协议栈（MCF/D2D/URPC）已完整逆向并真机验证字节正确；BLE 连接/绑定成功；但手环对 linkUp 与 ping 零回包，因为缺荣耀应用层 X25519 认证。好消息：认证是本地 X25519 ECDH+AES（libmbblink.so 已导出 `GenerateX25519KeyPair`/`GetX25519SharedKey`），可独立实现。明天优先用 androguard/字符串 逆向 `MbbLinkProtoV2Impl`/`AlgX25519` 的握手编排，搞清「公钥如何交换 + 共享密钥如何变 AES 会话密钥 + MCF 帧是否加密」，再移植进 `testapp/`。用户手机只能跑 apk，所有成果必须是可安装 APK。**

---

## 十一、风险与备选

| 风险 | 应对 |
|------|------|
| 握手编排复杂 / 白盒密钥难复现 | 优先用 Android 自带 X25519 + 标准 AES；白盒若只是密钥保护，绕过即可 |
| HiChain 必须绑荣耀账号 | 先任务 C 验证；若必须，评估能否用官方 App 一次性配对后我们的 App 复用绑定 |
| 手环 GATT 独占（不与官方 App 同时连） | 读取时与官方 App 错开；或解绑官方 App |
| 固件升级改协议 | 锁定用户手环固件版本逆向 |
| 法律边界 | 仅用户自有设备数据读取，不对外分发 |
