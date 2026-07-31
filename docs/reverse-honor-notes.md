# reverse-honor 目录说明（供接力 AI）

> 权威交接文档：`AI接力交接文档.md`（先读它）。
> 本目录已清理，仅保留**已验证/在用**的资产。早期错误方向的产物（0xfee0/LPv2 扫描 hits、
> 网页版 scanner、一次性脚本、androguard 缓存）已删除，勿再找。

## 保留的资产（都是有用的）

| 路径 | 用途 | 状态 |
|------|------|------|
| `honor-health.apk` | 源 APK（com.hihonor.health v17.22.1.301） | 只读源 |
| `android-sdk/` | 构建工具链（build-tools;34.0.0 + platform-34 + baksmali jar） | 在用 |
| `dexout/` | 全量 dex（classes.dex..classes27.dex），逆向用 | 规范来源 |
| `smali2/` | classes2.dex 的 baksmali 反汇编，**含认证类** `com/hihonor/hwddmp/connection/bluetooth/mbb/`（BtMbbManager、AuthInfo 等） | 明天任务 A 直接用 |
| `extracted/` | 主提取：`py/`(内嵌 Python SDK=协议真相)、`so/`(libmbblink/libpersimwear/libhisec...)、`assets/`、部分 dex | 规范来源 |
| `native/lib/` | libmbblink.so + libwhitebox.so（arm64+v7a），认证库子集 | 逆向用 |
| `testapp/` | **交付物**：原生 Android 测试/管理 App 源码 + `honor-band-test.apk` | 在用 |
| `honor-ble.js` | 协议栈参考实现（Web版，仅参考，手机不能跑网页） | 参考 |

## 认证类所在（明天任务 A 起点）
- `smali2/com/hihonor/hwddmp/connection/bluetooth/mbb/BtMbbManager*.smali`（含 `AuthInfo`）
- `MbbLinkProtoV2Impl` 在 classes2.dex → 用 baksmali 展开 smali2 已含
- `AlgX25519` 在 `dexout/classes9.dex`（需再 baksmali 该 dex）

## baksmali 用法（jar 无 Main-Class，需指定主类）
```
java -cp android-sdk/cmdline-tools/latest/lib/external/com/android/tools/smali/smali-baksmali/3.0.3/smali-baksmali-3.0.3.jar:$(find android-sdk/cmdline-tools/latest/lib/external -name '*.jar' | tr '\n' ':') \
  com.android.tools.smali.baksmali.Main d dexout/classes9.dex -o smali9
```
