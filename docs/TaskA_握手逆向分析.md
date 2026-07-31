# Task A 逆向成果：荣耀手环 MBB 认证握手（2026-07-31）

> 接 `AI接力交接文档.md` 的「任务 A」。基于 androguard 对 `dexout/*.dex`（27 个 dex）的分析。
> 工具：androguard 4.1.4（`DEX(open(path,'rb').read())` + `Analysis` + 指令反编译）。

## 〇、最重要的更正（务必先看）

1. **服务 UUID 写错了**。文档第四节写的 `c5f0ad48-cc27-48fd-9f67-8dc8e8f9b34fb` 是错的。
   真实 APK（`classes2.dex` 字符串池）引用的是：
   `c5f0ad48-cc27-48fd-9f67-8dc8e8f4a9cb`（末段 `…f4a9cb`，不是 `…f9b34fb`）。
   写/通知 UUID 不变：`2a5fc8bd-…-354bcdeccc70`、`a563d757-…-e8dde1f916aa`。
   → **testapp 若用错的服务 UUID，根本 discover 不到特征**，写入就会失败。需先核对 testapp 实际用的 UUID。

2. **testapp 用错了「帧」**。手环要的不是裸 MCF 帧，而是 **`MbbCommand`（MBB 命令层）** 包装的握手消息。
   真机握手走同一 `c5f0ad48` 通道，但消息是 `MbbHandShakeCmd.packAuthTypeRequest(...)` / `packHiChainData(...)`。
   **更正：opcode 不是 `0x128`/`0x133`**（之前误读）。真实 MbbCommand 头是 `serviceId(1B) + commandId(1B)`：
   - AuthTypeRequest：`serviceId=0x01, commandId=0x33`（hex 前缀 `"0133"`）
   - HiChainData（含公钥交换）：`serviceId=0x01, commandId=0x28`（hex 前缀 `"0128"`）
   文档里发的裸 `linkUp`/`_ping`（MCF 层）手环直接忽略 → 零回包。这同时解释了「字节正确却无响应」。

## 一、握手编排类（全在 `classes2.dex`）

- `com.hihonor.hwddmp.connection.bluetooth.auth.MbbHiChainAuthManager`
  —— 握手主编排。字段：`mHnDeviceAuthManager`(HiChain)、`mLocalAuthId`/`mPeerAuthId`(双方 authId 字节)、
  `mSecretInfo`(`SecretKeyUtils$KeyPairInfo`，含公私钥字节)、`mSessionId`。
- `com.hihonor.hwddmp.authmanager.AuthManager` —— `createCommonAuthConn(...)` 建立加密 BLE 链路（含 `createCommonAuthConn sessionKey null` 日志）。
- `com.hihonor.hwddmp.connection.bluetooth.mbb.MbbHandShakeCmd`
  —— `packAuthTypeRequest(int pairType, int, byte[] localAuthId)`、`packHiChainData(int, byte[] pubKey)`、
  `resolveAuthData(MbbCommand)`、`MbbHiChainResponse`(getDataType/getData)。
- `com.hihonor.hwddmp.connection.util.SecretKeyUtils`
  —— `getX25519SharedKey(byte[] priv, byte[] peerPub)`（Java 包装，底层调 `MbbGuard` native）。
- `com.hihonor.mbblink.guard.MbbGuard`（在 `classes5.dex`）—— native 包装：
  `generateX25519KeyPair()[B`、`getX25519SharedKey([B[B)[B`、`decrypt([B)String`（白盒解密硬编码密钥）。

## 二、握手时序（从 `MbbHiChainAuthManager` 反编译还原）

**构造 `MbbHiChainAuthManager(Context ctx, String sessionId, String peerAuthIdHex)`：**
- `mHnDeviceAuthManager = HnDeviceAuthManager.getInstance(ctx)`
- `mSessionId = sessionId`；`mPeerAuthId = HexUtils.hexToBytes(peerAuthIdHex)`
- `mLocalAuthId = HexUtils.hexToBytes(CommonUtils.getLocalDeviceId())` ← **本端 authId = 本机 deviceId**
- `mOperationParam = new OperationParameter(sessionId, "WEAR_MBB", createHnDevAuthCallback())`

1. `authDevice(deviceId, callback)`：
   - `connectHiChainAuthService()`：`new CountDownLatch(1)` → `HnDeviceAuthManager.connectDeviceAuthService(connCb)` → `await(timeout, MS)`（`onServiceConnected` 里 countDown）。
   - `mHnDeviceAuthManager.cancelRequest(sessionId)`（清理旧请求）。
   - `UserInfo("WEAR_MBB", mLocalAuthId)` → `isRegistered(ui)`；未注册则 `registerNewUser(ui)`（本地注册，无云端账号）。
   - `UserInfo("WEAR_MBB", mPeerAuthId)` → `isTrustPeer(ui)` 判定首配/重连。
   - 发包：`MbbHandShakeCmd.packAuthTypeRequest(pairType, authType, mLocalAuthId)` → `callback.onDataTransmit()`，整帧头 `01 33`。
2. `handleMessage(deviceId, bytes)`：`MbbCommand.decode` → 按 `commandId` 分发：
   - `0x33`（AuthTypeResponse）→ `handleAuthTypeResponse`；`0x28`（HiChainData）→ `handleHiChainData`。
3. `handleAuthTypeResponse`（收到 `01 33` 回包）：
   - 校验 `resp.getAuthType()==1`（否则 `onAuthFailed 402334`）。
   - `resp.getPairType()==1`(首配)：`SecretKeyUtils.generateX25519KeyPair()` 存入 `mSecretInfo`（`getPublicKeyBytes()`/`getPrivateKeyBytes()`）。
   - `resp.getPairType()==2`(重连)：`mOperationParam.setPeerAuthId(mPeerAuthId)` → `authenticatePeer(opParam, null, 32)`。
   - 发包：`packHiChainData(2, mSecretInfo.getPublicKeyBytes())` → `onDataTransmit()`（日志 `send: 0128`），**把本端 X25519 公钥发给手环**。
4. `handleHiChainData(cmd)`（收到 `01 28` 回包）：
   - `resolveAuthData(cmd)` → `MbbHiChainResponse`。
   - `getDataType()==1`：调 `HnDeviceAuthManager.processReceivedData(opParam, resp.getData())`（HiChain 引擎自己处理一轮）。
   - `getDataType()==2`：**算共享密钥** →
     `shared = SecretKeyUtils.getX25519SharedKey(mSecretInfo.getPrivateKeyBytes(), resp.getData())`（32 字节）；
     取 `Arrays.copyOf(shared, 6)`（前 6 字节）→ `CommonUtils.bytesToHexString` → hex 字符串；
     调 `HnDeviceAuthManager.bindPeer(opParam, sharedHex6, 32)`。
   - `authenticatePeer`/`bindPeer` 返回 `-2147483642`(0x80000006) 为**成功哨兵**；否则 `0x%08x` 打日志 + `onAuthFailed`。
   - 成功 → HiChain 引擎认为对端已绑定/已认证，并产出 **链路会话密钥（sessionKey）**。

## 三、回答文档「任务 A」的 4 个问题

**Q1：公钥在哪条 BLE 消息交换？**
- 同一 `c5f0ad48` 通道（写 `2a5fc8bd` / 通知 `a563d757`）。
- 本端公钥在 **`packHiChainData`** 消息里发出（opcode `0x128`/`0x133` 之一）；对端公钥在 `MSG_HI_CHAIN_TRANSPORT` 回包（`MbbHiChainResponse.getData()`）。
- 之前怀疑的「另一条专属认证通道」**不存在**——全是同一个 MBB 数据通道，只是用 MbbCommand 包装。

**Q2：共享密钥如何派生 AES 会话密钥？**
- X25519 ECDH：`shared = X25519(localPriv, peerPub)`，32 字节（`SecretKeyUtils.getX25519SharedKey`）。
- 这 32 字节**不是直接当 AES 密钥**：其**前 6 字节**喂给 `HnDeviceAuthManager.bindPeer(...)`，
  **真正的链路 sessionKey 由 HiChain（`deviceauthengine`）引擎内部产生**（native，位于 `com.hihonor.deviceauthengine`）。
- `mbbauth/core/j` 里的 AES 原语：`AES/CBC/PKCS5Padding`、`AES/CBC/PKCS7Padding`、`AES/GCM/NoPadding`、
  `HMACSHA256`、`SHA-256`(可截断)。`b0.createSessionKey` 用 `j.b`(AES-CBC) 或 `j.d`(AES-GCM) 生成会话密钥，
  密钥/IV 材料来自白盒解密（`MbbGuard.decrypt`）的硬编码 blob + `j.a()`/`j.b()`。
- **结论**：独立复刻「shared→sessionKey」需逆向 `HnDeviceAuthManager` 内部派生；或**直接调用系统 HiChain 引擎**（见 Q4）。

**Q3：认证后 MCF 帧是否加密？**
- 是。存在 `encryptData/decryptData` 路径（`b0.b` 内 `encryptData: sessionKey is null` 日志证明有 sessionKey 加密层）。
- 加密算法为 AES（CBC 或 GCM，原语齐全）。具体模式需再确认 `AuthManager.createCommonAuthConn` 用哪个 `j.*` 方法。
- 加密范围：整个 MBB 载荷（MbbCommand 内的数据）在认证后加解密；未认证时手环不处理任何帧。

**Q4：HiChain 是否必须？**
- **必须**，但它是**本地设备认证引擎**，操作类型 `WEAR_MBB`，代码路径 `registerNewUser`/`isTrustPeer`/`bindPeer`，**无需荣耀账号/云端**。
- **【重大发现，2026-07-31】HiChain 引擎整套打包在 APK 内，不是外部系统服务。**
  - `HnDeviceAuthManager.getInstance(ctx)` 只是**客户端 shim**：`connectDeviceAuthService` 里
    `new Intent(ctx, HnDeviceAuthService.class)` + `bindService(...)`，即**显式 Intent 绑到 APK 内自带的 Service**。
  - `HnDeviceAuthService`（`classes23.dex`）是**完整的 `IHichainService.Stub` 实现**（`bind`/`processReceivedData`/`authenticatePeer` 等 AIDL 方法体都在）。
  - Manifest 里该 service：`enabled=true, exported=false`（**私有服务，仅同 APK/同 UID 可绑**）。
  - 全量 `loadLibrary` 扫描：`deviceauthengine.*` **不直接 load 任何 .so**；其底层 native 通过
    `WhiteBoxNative`→`libwhitebox.so`、`MbbGuard`→`libmbblink.so`（+ `hisec`/`persimwear`）间接使用。
- **结论对 Task B 的影响**：不需要「调系统服务」也不需要「有没有权限」。把
  `com.hihonor.deviceauthengine.*` 这批类 + 依赖的 `libwhitebox.so`/`libmbblink.so`（及其 Java 包装
  `WhiteBoxNative`/`MbbGuard`）一起打进 testapp，`HnDeviceAuthService` 在**本进程内**跑起来即可。
  Task C 的「权限/签名」风险大幅降低——真正要验证的是**这些 native 库在非荣耀签名/非荣耀机型上能否正常初始化**（白盒可能校验包名/签名）。

## 四、给 Task B（移植进 testapp）的建议路线

**路线 1（推荐，最快）：复用系统 HiChain 引擎**
**做法（已据 5.x + Q4 落实为可执行步骤）：把荣耀自带的认证代码整体搬进 testapp、本进程内运行。**
1. 从 APK 抽取并打包这些类到 testapp：
   - `com.hihonor.deviceauthengine.*`（含 `HnDeviceAuthManager`、`HnDeviceAuthService`、`IHichainService(+Stub/Proxy)`、`UserInfo`、`OperationParameter`、`SessionInfo`、callback 接口、`ValidationUtils`/`LogUtils`）。
   - `com.hihonor.hwddmp.connection.bluetooth.mbb.*`（`MbbCommand`、`MbbHandShakeCmd`、`MbbHiChainResponse`、`MbbAuthTypeResponse`）。
   - `com.hihonor.hwddmp.connection.bluetooth.auth.MbbHiChainAuthManager`（编排）+ `MbbAuthCallback`。
   - `com.hihonor.hwddmp.connection.util.SecretKeyUtils`、`com.hihonor.mbblink.guard.MbbGuard`、`com.hihonor.hwddmp.utils.WhiteBoxNative` 及其它 `HexUtils/CommonUtils/SafetyUtils` 等工具类。
2. 打入 native：`libmbblink.so`、`libwhitebox.so`（+ 若初始化需要 `libhisec.so`/`libpersimwear.so`）到 testapp 的 `jniLibs/arm64-v8a`。
3. 在 testapp `AndroidManifest.xml` 声明 `<service android:name="com.hihonor.deviceauthengine.HnDeviceAuthService" android:exported="false"/>`。
4. 认证流程（照搬 `MbbHiChainAuthManager`）：
   `new MbbHiChainAuthManager(ctx, sessionId, peerAuthIdHex)` → `authDevice(deviceId, cb)`；
   `cb.onDataTransmit(addr, mbbData)` 里把 `mbbData` 写到 BLE 特征 `2a5fc8bd`；
   BLE 通知 `a563d757` 收到的字节回喂 `handleMessage(deviceId, bytes)`；
   直到 `bindPeer/authenticatePeer` 返回 `0x80000006` → 认证成功，拿到 sessionKey。
5. 之后 MBB 载荷加解密走 `AuthManager.createCommonAuthConn` 的 sessionKey（AES 模式待 6 节确认）。
- **优点**：无需复刻白盒/HiChain 密码学，直接用原厂实现；`getMbbData()` 已给出精确发包字节。
- **唯一风险**：`libwhitebox.so`/`libmbblink.so` 可能校验调用方**包名/签名**。缓解：Task C 先在真机验证 native 能否初始化；必要时把 testapp 包名伪装为 `com.hihonor.health`（仅用于本人自测）。

**路线 2（纯自研，不依赖 HiChain）——已降级为备选**
- Android Conscrypt `X25519PrivateKeyParameters` 做 ECDH；复刻 `SecretKeyUtils.getX25519SharedKey` 取 32 字节；
  复刻 `bindPeer` 的 sessionKey 派生（在 `HnDeviceAuthService` 内的 Java + `libwhitebox` native，未逆向）——风险高。既然路线 1 是纯搬运原厂代码，路线 2 不再优先。

## 五、MbbCommand / 握手帧 字节格式（已完整逆向）

### 5.1 MbbCommand 整帧结构
`MbbCommand(byte serviceId, byte commandId, byte[] payload)`。整帧 = `serviceId(1B) | commandId(1B) | payload(变长)`。
- `decode(byte[])`：`serviceId=b[0]`，`commandId=b[1]`，`payload=b[2..]`（要求 `len>=2`）。
- `encode(byte[])`：把整帧数组还原为 MbbCommand（`serviceId=b[0]`, `commandId=b[1]`, 其余为 payload）。
- `getMbbData()`：把 MbbCommand 序列化回 `serviceId|commandId|payload` 整帧（这就是最终写到 BLE 特征的字节）。
- **加密变体**：`encode([B,ctx,addr,counter)` 走 CBC，`encodeGcm(...)` 走 GCM。加密时会在 payload 首插入
  标记字节 `0x7D`(=125) + 随机 IV/counter；解密侧 `decode(...,ctx,...)` 检测 `payload[0]==0x7C`(=124) 触发
  `checkDesEncryptCommand`。**握手期（AuthType/HiChain）是明文**，加密只在会话建立后。

### 5.2 payload = TLV 序列（type/len/value）
`builderTlvMap(hexString)`：把 payload 的 hex 串按 TLV 逐段切：
- **Type**：1 字节（2 个 hex 字符）。
- **Length**：`getLengthAndPosition` 解析，配合 `calLength` 的 **varint/LEB128**（每字节低 7 位，最高位 `0x80` 表示后续还有；`calLength` 里 `rem 128` / `div 128` 生成，最后一段不带续位、其余段 `+128`）。绝大多数握手字段长度 <128，即单字节长度。
- **Value**：`len` 个字节。
- 结果是 `Map<Integer type, String hexValue>`。

### 5.3 packAuthTypeRequest(int pairType, int p2, byte[] localAuthId) —— 首包
拼出的 hex 串（`encode` 成 MbbCommand）：
```
"0133"                       // serviceId=01, commandId=33  (整帧头前两字节)
+ TLV1: "01"+intToHex(01)+intToHex(pairType)       // type=0x01,len=0x01,value=pairType
+ TLV2: "02"+intToHex(01)+intToHex(p2)             // type=0x02,len=0x01,value=p2
+ TLV3: "03"+calLength(localAuthId)+hex(localAuthId) // type=0x03,len=varint,value=本端 authId
```
（源码里 v3=TLV1(type01), v4=TLV2(type02), v5=TLV3(type03)，最后 `"0133"+v3+v4+v5`。）

### 5.4 packHiChainData(int dataType, byte[] data) —— 公钥/HiChain 数据包
```
"0128"                       // serviceId=01, commandId=28
+ TLV1: "01"+intToHex(01)+intToHex(dataType)       // type=0x01,len=0x01,value=dataType(1 或 2)
+ TLV2: "02"+calLength(data)+hex(data)             // type=0x02,len=varint,value=data
```
- 发本端 X25519 公钥时：`dataType=2`, `data=mSecretInfo.getPublicKeyBytes()`（见 `handleAuthTypeResponse`，日志 `"send: 0128"`）。
- HiChain 引擎中转数据时：`dataType=1`, `data=` 引擎产出的字节（见 `onDataTransmit`，也走 `packHiChainData(2,...)` 分支）。

### 5.5 响应解析（TLV type → 字段）
`resolveAuthTypeResponse(cmd)` → `MbbAuthTypeResponse`（读 payload TLV）：
- type `0x01` → `pairType`（parseInt hex）
- type `0x02` → `authType`（parseInt hex）
- type `0x03` → `peerAuthId`（hexToBytes，**对端 authId 字节**）

`resolveAuthData(cmd)` → `MbbHiChainResponse`：
- type `0x01` → `dataType`（parseInt hex：1=HiChain 中转，2=对端公钥）
- type `0x02` → `data`（hexToBytes）

### 5.6 opcode 分发（handleMessage → MbbCommand.decode → 按 commandId）
- `commandId=0x33`（AuthTypeResponse）→ `handleAuthTypeResponse`：
  校验 `getAuthType()==1`；`pairType==1`(首配) 生成 X25519 密钥对存 `mSecretInfo`；
  `pairType==2`(重连) 调 `HnDeviceAuthManager.authenticatePeer(opParam, null, 32)`；
  然后 `packHiChainData(2, mSecretInfo.getPublicKeyBytes())` 发公钥（日志 `send: 0128`）。
- `commandId=0x28`（HiChainData）→ `handleHiChainData`：
  `dataType==1` → `HnDeviceAuthManager.processReceivedData(opParam, data)`；
  `dataType==2` → `shared=getX25519SharedKey(mSecretInfo.privateKeyBytes, data)`；`Arrays.copyOf(shared,6)`
  → `CommonUtils.bytesToHexString` → `HnDeviceAuthManager.bindPeer(opParam, sharedHex6, 32)`。
  返回码 `-2147483642`(0x80000006) 为成功哨兵，其余 `0x%08x` 打日志并 `onAuthFailed`。

> 注：`bindPeer/authenticatePeer/processReceivedData` 的第 3 参 `32` 与共享密钥截断到 6 字节，都是 HiChain 引擎约定。
> 真正链路 sessionKey 由 APK 内自带的 `HnDeviceAuthService`（`IHichainService.Stub`）产生，底层经 `libwhitebox.so`/`libmbblink.so`。

## 六、HiChain 引擎位置（Task B 关键，已确认）

- `HnDeviceAuthManager`（`classes23.dex`）= **客户端 shim**；`connectDeviceAuthService` = `new Intent(ctx, HnDeviceAuthService.class)` + `bindService`（显式 Intent，绑 **APK 内自带 Service**）。
- `HnDeviceAuthService`（`classes23.dex`）= **完整 `IHichainService.Stub`**，`bind/authenticatePeer/processReceivedData/bindDevice` 方法体齐全，跑在**同 APK 进程**。
- Manifest：`HnDeviceAuthService` `enabled=true exported=false`（私有服务）。
- `deviceauthengine.*` **不直接 `loadLibrary`**；native 经 `WhiteBoxNative`→`libwhitebox.so`、`MbbGuard`→`libmbblink.so`（可能 + `libhisec.so`/`libpersimwear.so`）。
- APK 内 .so：`libmbblink / libwhitebox / libhisec / libhonordetect / libpersimwear`（`arm64-v8a` + `armeabi-v7a`）。
- **含义**：Task B 路线 1 = 把这套类 + so 搬进 testapp 本进程运行，**无需系统权限**；唯一风险是 so 的包名/签名校验。

## 七、待办（下一步要挖的）

- [x] `MbbCommand` / `MbbHandShakeCmd` / `MbbHiChainResponse` 字节格式 —— 见第五节，已完整。
- [x] HiChain 引擎是否需系统权限 —— 否，引擎打包在 APK 内、私有 Service、本进程运行（见第六节）。
- [ ] `AuthManager.createCommonAuthConn` 用哪个 `j.*` 方法做链路加解密（定 AES 模式/IV 来源；结合 5.1 的 `encode`(CBC)/`encodeGcm`(GCM) 与 `0x7D`/`0x7C` 标记）。
- [ ] `libwhitebox.so`/`libmbblink.so` 是否校验调用方包名/签名（决定 testapp 能否直接复用；Task C 真机验，必要时伪装包名 `com.hihonor.health`）。
- [ ] 清点路线 1 需要搬运的完整类清单 + 依赖闭包（用 dex 依赖分析生成）。
- [x] testapp 服务 UUID 已核对：layout 里用的是真值 `…f4a9cb`（正确），根因是帧格式+认证，非 UUID。

## 八、环境提示

- androguard 4.1.4 的 API：`DEX(open(p,'rb').read())`；方法指令 `m.get_instructions()`，`ins.get_name()`/`ins.get_operands()`；APK 用 `from androguard.core.apk import APK`，`a.get_services()`/`a.get_android_manifest_xml()`。
- 跨 dex 全量 xref 很慢（162k 类 / 7 分钟且类名查找易错）；定位调用方改用「在调用方 dex 里扫 `invoke-*` 操作数含方法名字符串」更快。
- `classes5.dex`：mbblink/mbblink guard/MbbGuard、mbbauth/core（b0/j/p 加密原语）。
- `classes2.dex`：hwddmp 连接层、MbbHiChainAuthManager、AuthManager、MbbHandShakeCmd、MbbCommand。
- `classes23.dex`：`com.hihonor.deviceauthengine`（HiChain 引擎 **完整实现 + 私有 Service**，本进程运行；非纯 native）。
