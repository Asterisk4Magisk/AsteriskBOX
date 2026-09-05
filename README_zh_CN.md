[English](README.md) | 简体中文

# AsteriskBOX

一个 Android sing-box GUI 客户端。VPN Service 模式使用 [AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite)，ROOT 模式运行 [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases)构建的 Android 二进制文件。

## Telegram 频道

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## 功能

- VPN Service、TPROXY(ROOT)、TUN(ROOT)、eBPF(ROOT)、TUN2SOCKS(ROOT) 和 BPF2SOCKS(ROOT) 运行模式
- 通过二维码、本地文件或 URL 订阅导入并管理严格的 sing-box JSON 配置
- 出站、DNS、路由、规则集和资源管理
- 通过 sing-box 官方命令 API 获取状态、流量、连接、代理选择和延迟测试
- Material 3 Compose UI

## 运行模式

### VPN Service

- 无需 root 权限。
- 通过 AndroidLibBoxLite 和 Android `VpnService` 在应用进程中运行 sing-box。
- 可选的 Hev TUN 路径使用 `hev-socks5-tunnel`。

### TPROXY(ROOT)

- 运行内置 sing-box 二进制文件和 TPROXY 入站。
- 使用 iptables 和策略路由处理透明代理流量。

### TUN(ROOT)

- 运行内置 sing-box 二进制文件并创建固定 TUN 设备 `asterisk0`。
- 使用 sing-box 托管的 `auto_route` 和 `auto_redirect`，不再由应用管理透明路由。
- 支持 System、gVisor 和 Mixed TUN 栈。
- 所选规则集中的 IP CIDR 会写入 `route_exclude_address_set`，域名规则不生效。
- 可加入准确的下游接口名以接管热点和网络共享流量。

### eBPF(ROOT)

- 使用 reF1nd sing-box eBPF 入站，不需要 TUN 设备或本地 SOCKS5 中间层。
- 本机流量采用 TC 数据平面，准确的下游接口可选用 `socket_assign`。
- 与 TUN 模式共用规则集选择，并将其中的 IP CIDR 写入 `bypass_rule_set`；域名规则不生效。
- 是否可用取决于设备内核、cgroup v2 与 eBPF 支持情况。

### TUN2SOCKS(ROOT)

- 使用 `hev-socks5-tunnel` 创建固定 TUN 设备 `asterisk0`。
- 将隧道流量送入本地 sing-box SOCKS5 入站。

### BPF2SOCKS(ROOT)

- 使用 eBPF 和 native `bpf2socks` helper，不创建 TUN 设备。
- 将接管的 TCP、UDP 流量送入本地 sing-box SOCKS5 入站。
- 启动前要求 eBPF 能力探测通过。

### asteriskd

- 监听本地 IPv4/IPv6 地址和热点接口变化，并刷新相应的 iptables 规则或 BPF map。
- 服务停止时清理当前 ROOT 模式负责的网络规则。

## 资源文件

- ROOT 运行文件存储在应用私有的 `files/sing-box` 目录。
- 内置的 reF1nd sing-box ROOT 核心可在资源管理中替换。
- Direct CIDR 和自定义资源文件可在本地替换或通过配置的 URL 更新；规则集仍属于 sing-box JSON 配置。

## 开发

构建前初始化 submodule：

```bash
git submodule update --init --recursive
```

使用 Android Studio 或 Gradle wrapper 构建：

```powershell
.\gradlew.bat assembleDebug
```

macOS 或 Linux：

```bash
./gradlew assembleDebug
```

构建会解析已配置的 AndroidLibBoxLite 和 reF1nd sing-box 版本，构建 native helper submodule，并生成 ABI split APK 和 universal APK。

如果 Gradle 找不到 Android NDK，请通过 Android Studio、`local.properties` 中的 `ndk.dir` 或 `ANDROID_NDK_HOME` 配置。

## WSA

```bash
appops set org.asterisk.zcc.abox ACTIVATE_VPN allow
```

## 许可

[GPL-3.0](LICENSE)

## 致谢

- [@SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [@reF1nd/sing-box-releases](https://github.com/reF1nd/sing-box-releases)
- [@Asterisk4Magisk/AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite)
- [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@android/material3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
