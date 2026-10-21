<!-- fanxing-style:start -->
<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://fanxing724.github.io/new-make/fanxing724/projects/honor-band-9/cover.light.svg">
  <img alt="荣耀手环 9 · 探索手记" src="https://fanxing724.github.io/new-make/fanxing724/projects/honor-band-9/cover.svg" width="100%">
</picture>

**等审核的时候，好奇心已经先出发了。**

因为开发者资格还在审核，又想先玩玩手环，于是有了这些尝试。这里保留探索设备与数据的过程，已经观察到的结果和还没验证的设想分别记录。

<sub>番星 / 星轨手记 · <a href="https://fanxing724.github.io/new-make/fanxing724/projects/style.html">风格样板</a> · <a href="https://fanxing724.github.io/">个人小站</a></sub>
<!-- fanxing-style:end -->

# honor-band-9

Reverse-engineering notes and tooling for reading **your own** HONOR Band health data
(steps / heart rate / SpO2 / stress / sleep) over its private BLE (MBB) protocol, so an
independent Android app can talk to the band without the official 荣耀运动健康 app.

> **Scope & ethics.** This is interoperability research on a device the author owns, to
> access the author's own data. It contains **no** copyrighted HONOR binaries — no APK,
> no `.so`, no decompiled `.dex`/`.smali`. Only original analysis, notes, and tooling are
> published here (see `.gitignore`). Everything must be regenerated locally from an APK
> you legally obtained.

## What's here

| Path | What |
|------|------|
| `docs/TaskA_握手逆向分析.md` | **Main deliverable** — full reverse of the X25519 + HiChain auth handshake, MbbCommand wire format, and the port plan. |
| `docs/AI接力交接文档.md` | Project handoff / task plan (A–D). |
| `docs/reverse-honor-notes.md` | Working notes on the local (git-ignored) analysis tree. |
| `scripts/androguard/*.py` | androguard 4.1.4 scripts used to disassemble & map the auth classes. |
| `scripts/honor-ble.js` | Reference implementation of the MCF/D2D/URPC stack (study aid). |
| `testapp/` | Native Android test app **source** (BLE scan/connect/write/notify). Build with `testapp/build.sh`. |

## Key findings (summary)

- The band ignores raw MCF `linkUp`/`_ping`; it requires an **app-layer auth handshake**
  (X25519 ECDH orchestrated through a local HiChain engine) **and** frames wrapped as
  `MbbCommand` (`serviceId | commandId | TLV payload`), not raw MCF.
- Handshake frames: `packAuthTypeRequest` → header `01 33`; `packHiChainData` (carries the
  X25519 public key) → header `01 28`. Response TLVs and the shared-key→`bindPeer` flow are
  documented in `docs/TaskA_握手逆向分析.md`.
- **The whole HiChain engine (`HnDeviceAuthService`) is bundled inside the health APK as a
  private, in-process service** — not an external system service. So a port can run the
  original auth code in-process; the main open risk is whether the native libs
  (`libwhitebox.so` / `libmbblink.so`) validate the caller's package/signature.
- Correct BLE service UUID: `c5f0ad48-cc27-48fd-9f67-8dc8e8f4a9cb`
  (write `2a5fc8bd-…-354bcdeccc70`, notify `a563d757-…-e8dde1f916aa`).

## Status

- Task A (handshake reverse) — **done** (`docs/TaskA_握手逆向分析.md`).
- Task B (port auth into the test app) — in progress.
- Task C (on-device verification that native libs init outside the official app) — pending.

## Reproducing the analysis

You need an APK you legally obtained + `androguard==4.1.4`:

```bash
pip install androguard==4.1.4
# then run the scripts against your local dexout/*.dex, e.g.
python3 scripts/androguard/dump_dispatch.py
```

## License

MIT — see `LICENSE`. Applies to the original code/notes in this repo only. HONOR trademarks
and any HONOR software referenced remain the property of their owner.
