English | [简体中文](README_zh_CN.md)

# AsteriskBOX

An Android sing-box GUI client. VPN Service mode uses [AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite); ROOT modes execute the [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases) build for Android.

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Features

- VPN Service, TPROXY(ROOT), TUN(ROOT), eBPF(ROOT), TUN2SOCKS(ROOT), and BPF2SOCKS(ROOT) run modes
- Strict sing-box JSON configurations from QR code, local file, or URL subscription
- Official sing-box command API for status, traffic, connections, modes, proxy selection, and delay tests
- ROOT start-on-boot script generation through Magisk `service.d`
- Material 3 Compose UI

## Run Modes

### VPN Service

- Works without root permission.
- Runs sing-box in the app process through AndroidLibBoxLite JNI and its local command server.
- Uses Android `VpnService`; the optional Hev TUN path continues to use `hev-socks5-tunnel`.

### TPROXY(ROOT)

- Runs the bundled sing-box Android binary with a TPROXY inbound.
- Uses iptables and policy routing for transparent proxy traffic.

### TUN(ROOT)

- Runs the bundled sing-box Android binary with the fixed TUN device `asterisk0`.
- Keeps sing-box `auto_route` disabled and applies app-managed iptables and policy routing rules.
- Supports the System, gVisor, and Mixed TUN stacks.

### eBPF(ROOT)

- Uses the reF1nd sing-box eBPF inbound to attach cgroup socket-address programs directly.
- Does not use a TUN device, TProxy, iptables, policy routing, a Bridge helper, or a local SOCKS5 intermediary.
- The direct-bypass switch copies rule sets routed to the direct outbound into `bypass_rule_set`.
- Optional shared-network TC uses exact downstream interface names. Wildcards and interface prefixes are not supported.
- Availability depends on device kernel, cgroup v2, and eBPF support.

### TUN2SOCKS(ROOT)

- Uses `hev-socks5-tunnel` to create `asterisk0`.
- Sends tunnel traffic to a local sing-box SOCKS5 inbound.

### BPF2SOCKS(ROOT)

- Uses eBPF and the native `bpf2socks` helper to send TCP and UDP traffic to a local sing-box SOCKS5 inbound.
- Requires the eBPF capability probe to pass before startup.

### ROOT address monitor

ROOT modes use the native `asteriskd` monitor to maintain local-address bypass rules and optional IPv6 state. Runtime binaries, configuration, PID files, helpers, and logs remain in the app-private `files/sing-box` directory. Only the boot entry script is installed outside it at `/data/adb/service.d/asteriskbox_start.sh`.

## Resource Files

- The bundled ROOT core is the reF1nd sing-box Android binary selected by `ProjectConfig.SING_BOX_VERSION` and may be manually replaced from the resource page.
- Direct CIDR IPv4/IPv6 files and custom resource files can be replaced or updated from configured URLs.
- Rule sets remain part of the sing-box JSON configuration.

## Development

Initialize submodules before building:

```bash
git submodule update --init --recursive
```

Build with Android Studio or the Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

The build resolves the AndroidLibBoxLite version configured by `ProjectConfig.ANDROID_LIB_BOX_LITE_VERSION`, downloads the reF1nd ROOT core version configured by `ProjectConfig.SING_BOX_VERSION` for all supported ABIs, builds the native helper submodules, and produces ABI split APKs plus a universal APK.

If Gradle cannot find Android NDK, set `ndk.dir` in `local.properties`, set `ANDROID_NDK_HOME`, or install an NDK under the Android SDK.

## WSA

```bash
appops set org.asterisk.zcc.abox ACTIVATE_VPN allow
```

## License

[GPL-3.0](LICENSE)

## Credits

- [@SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [@reF1nd/sing-box-releases](https://github.com/reF1nd/sing-box-releases)
- [@Asterisk4Magisk/AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite)
- [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@android/material3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
