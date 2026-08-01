[English](README.md) | 简体中文

# AsteriskBOX

一个 Android sing-box GUI 客户端。VPN Service 模式使用 [AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite)，ROOT 模式运行 [reF1nd sing-box](https://github.com/reF1nd/sing-box-releases)构建的 Android 二进制文件。

## Telegram 频道

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## 功能

- VPN Service、TPROXY(ROOT)、TUN(ROOT)、TUN2SOCKS(ROOT) 和 BPF2SOCKS(ROOT) 运行模式
- 通过二维码、本地文件或 URL 订阅导入严格的 sing-box JSON 配置
- 通过 sing-box 官方命令 API 获取状态、流量、连接、模式、代理选择和延迟测试
- 通过 Magisk `service.d` 脚本支持 ROOT 模式开机自启
- Material 3 Compose UI

## 运行模式

### VPN Service

- 无需 root 权限。
- 通过 AndroidLibBoxLite JNI 和本地命令服务在应用进程中运行 sing-box。
- 使用 Android `VpnService`；可选 Hev TUN 路径继续使用 `hev-socks5-tunnel`。

### TPROXY(ROOT)

- 运行内置 sing-box Android 二进制文件和 TPROXY 入站。
- 使用 iptables 和策略路由处理透明代理流量。

### TUN(ROOT)

- 运行内置 sing-box Android 二进制文件并创建固定 TUN 设备 `asterisk0`。
- 不启用 sing-box `auto_route`，使用应用托管的 iptables 和策略路由规则。
- 支持 System、gVisor 和 Mixed TUN 栈。

### TUN2SOCKS(ROOT)

- 使用 `hev-socks5-tunnel` 创建 `asterisk0`。
- 将隧道流量送入本地 sing-box SOCKS5 入站。

### BPF2SOCKS(ROOT)

- 使用 eBPF 和 native `bpf2socks` helper 将 TCP、UDP 流量送入本地 sing-box SOCKS5 入站。
- 启动前要求 eBPF 能力探测通过。

### ROOT 地址监控

所有 ROOT 模式使用 native `asteriskd` 维护本地地址绕过规则和可选 IPv6 状态。运行二进制、配置、PID、辅助程序和日志统一保存在应用私有 `files/sing-box` 目录；只有开机入口脚本写入 `/data/adb/service.d/asteriskbox_start.sh`。

## 资源文件

- 内置 ROOT 核心为 `ProjectConfig.SING_BOX_VERSION` 选择的 reF1nd sing-box Android 二进制文件，并可在资源页面手动替换。
- Direct CIDR IPv4/IPv6 和自定义资源文件可手动替换或通过配置 URL 更新。
- Rule Set 保持在 sing-box JSON 配置中。

## 开发

构建前初始化 submodule：

```bash
git submodule update --init --recursive
```

使用 Android Studio 或 Gradle wrapper 构建：

```powershell
.\gradlew.bat assembleDebug
```

构建会解析 `ProjectConfig.ANDROID_LIB_BOX_LITE_VERSION` 配置的 AndroidLibBoxLite 版本，为全部支持 ABI 下载 `ProjectConfig.SING_BOX_VERSION` 配置的 reF1nd ROOT 核心，构建 native helper submodule，并生成 ABI split APK 和 universal APK。

如果 Gradle 找不到 Android NDK，请在 `local.properties` 中设置 `ndk.dir`，设置 `ANDROID_NDK_HOME`，或在 Android SDK 下安装 NDK。

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
