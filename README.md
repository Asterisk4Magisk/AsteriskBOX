English | [简体中文](README_zh_CN.md)

# AsteriskBOX

An Android sing-box GUI client. VPN Service mode uses [AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite); ROOT modes execute the [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases) build for Android.

## Telegram Channel

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Features

- VPN Service, TPROXY(ROOT), TUN(ROOT), eBPF(ROOT), TUN2SOCKS(ROOT), and BPF2SOCKS(ROOT) run modes
- Import and manage strict sing-box JSON configurations from QR codes, local files, or URL subscriptions
- Outbound, DNS, routing, rule-set, and resource management
- Live status, traffic, connections, proxy selection, and delay tests through the official sing-box command API
- Material 3 Compose UI

## Run Modes

### VPN Service

- Works without root permission.
- Runs sing-box in the app process through AndroidLibBoxLite and Android `VpnService`.
- The optional Hev TUN path uses `hev-socks5-tunnel`.

### TPROXY(ROOT)

- Runs the bundled sing-box binary with a TPROXY inbound.
- Uses iptables and policy routing for transparent proxy traffic.

### TUN(ROOT)

- Runs the bundled sing-box binary with the fixed TUN device `asterisk0`.
- Uses sing-box-managed `auto_route` and `auto_redirect` instead of app-managed transparent routing.
- Supports the System, gVisor, and Mixed TUN stacks.
- Selected rule-set IP CIDRs are passed to `route_exclude_address_set`; domain rules do not apply.
- Exact downstream interface names can be included for hotspot and tethering traffic.

### eBPF(ROOT)

- Uses the reF1nd sing-box eBPF inbound without a TUN device or local SOCKS5 intermediary.
- Uses the TC data plane for local traffic and optional `socket_assign` for exact downstream interfaces.
- Shares TUN mode's rule-set selection and passes its IP CIDRs to `bypass_rule_set`; domain rules do not apply.
- Availability depends on device kernel, cgroup v2, and eBPF support.

### TUN2SOCKS(ROOT)

- Uses `hev-socks5-tunnel` to create the fixed TUN device `asterisk0`.
- Sends tunnel traffic to a local sing-box SOCKS5 inbound.

### BPF2SOCKS(ROOT)

- Uses eBPF and the native `bpf2socks` helper without creating a TUN device.
- Sends captured TCP and UDP traffic to a local sing-box SOCKS5 inbound.
- Requires the eBPF capability probe to pass before startup.

### asteriskd

- Watches local IPv4/IPv6 addresses and tethering interfaces, then refreshes the relevant iptables rules or BPF maps.
- Cleans up networking rules owned by the active ROOT mode when the service stops.

## Resource Files

- ROOT runtime files are stored in the app-private `files/sing-box` directory.
- The bundled reF1nd sing-box ROOT core can be replaced from Resource Management.
- Direct CIDR and custom resource files can be replaced locally or updated from configured URLs; rule sets remain part of the sing-box JSON configuration.

## Development

Initialize submodules before building:

```bash
git submodule update --init --recursive
```

Build with Android Studio or the Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The build resolves the configured AndroidLibBoxLite and reF1nd sing-box versions, builds the native helper submodules, and produces ABI split APKs plus a universal APK.

If Gradle cannot find the Android NDK, configure it through Android Studio, `ndk.dir` in `local.properties`, or `ANDROID_NDK_HOME`.

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
