# bpf2socks

`bpf2socks` is a root Android/Linux traffic bridge. It uses cgroup and socket eBPF programs to retain original destinations, then forwards TCP and UDP through a SOCKS5 upstream. It supports UID policy, private/direct CIDR policy, interface policy, DNS transaction tracking, connected UDP, full-cone UDP reply bindings, worker sharding, and runtime statistics.

The source root includes a standalone GNU Make build for Linux and Android. A parent Android project may still compile all top-level `.c` files together as a PIE executable, commonly packaged as `libbpf2socks.so`.

## Standalone build

GNU Make and a C compiler are required. Run all commands from the repository root.

### Linux

```sh
make
# Equivalent explicit target:
make linux
```

The output is `build/linux/bpf2socks`. Override standard Make variables such as `CC`, `CPPFLAGS`, `CFLAGS`, `LDFLAGS`, and `LDLIBS` when needed.

### Android

Install the Android NDK, then expose it through `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`.

```sh
# Build arm64-v8a, armeabi-v7a, x86, and x86_64:
make android

# Build one ABI:
make android ABI=arm64-v8a

# Override the default API level 24:
make android ABI=arm64-v8a ANDROID_API=26
```

Android outputs are written to `build/android/<abi>/libbpf2socks.so`. They are PIE executables despite the `.so` packaging suffix. Android-specific flag overrides are available through `ANDROID_CPPFLAGS`, `ANDROID_CFLAGS`, `ANDROID_LDFLAGS`, and `ANDROID_LDLIBS`.

Linux, macOS, and Windows NDK hosts are supported. On Windows, run GNU Make from an environment with a POSIX shell, such as MSYS2 or Git Bash, and use forward-slash paths when setting an NDK directory explicitly.

### Clean

```sh
make clean
```

This removes only the repository-local `build/` directory.

## Command line

```text
bpf2socks --probe [--config FILE]
bpf2socks --start --config FILE --pid FILE
bpf2socks --stop --config FILE --pid FILE
bpf2socks --stats --pid FILE
```

`--probe` prints a JSON capability result and can use a configuration to probe its requested IPv6/policy features. `--start` loads BPF state and starts the bridge. `--stop` stops the PID and cleans resources described by the same configuration. `--stats` prints JSON bridge counters. Missing required arguments return `2`; runtime failures return `1`; success returns `0`.

## Configuration example

```json
{
  "version": 1,
  "socksHost": "127.0.0.1",
  "socksPort": 1080,
  "bridgeListenAddress": "0.0.0.0",
  "bridgePort": 65532,
  "pinnedObjectDir": "/sys/fs/bpf/example/bpf2socks",
  "cgroupPath": "/sys/fs/cgroup",
  "enableIpv6": true,
  "enableDnsHijack": true,
  "debugStats": false,
  "tokenIpv4Prefix": "127.0.0.0/8",
  "tokenIpv6Prefix": "fd7a:7374:6572:6973::/64",
  "workerCount": 0,
  "tcpBufferSize": 65536,
  "maxTcpSessions": 4096,
  "tcpConnectTimeoutMilliseconds": 10000,
  "tcpIdleTimeoutMilliseconds": 300000,
  "udpSocketBufferSize": 524288,
  "udpBatchSize": 10,
  "maxUdpSessions": 4096,
  "maxUdpBindings": 16384,
  "udpIdleTimeoutSeconds": 60,
  "maxUdpPendingBytes": 67108864,
  "dnsTransactionTimeoutMilliseconds": 60000,
  "hotspotInterfacePrefixes": ["wlan+"],
  "ignoredInterfaces": ["lo"],
  "proxyPrivateCidrsV4": ["10.0.0.0/8"],
  "bypassPrivateCidrsV4": ["192.168.0.0/16"],
  "proxyPrivateCidrsV6": ["fd00::/8"],
  "bypassPrivateCidrsV6": ["fe80::/10"],
  "policy": {
    "mode": 2,
    "uids": [],
    "bypassUids": [],
    "bypassDirectCidrs": false,
    "directCidrPathV4": "/data/local/tmp/example/direct-v4.txt",
    "directCidrPathV6": "/data/local/tmp/example/direct-v6.txt"
  }
}
```

### Required top-level fields

| Field | Description |
| --- | --- |
| `socksHost` | SOCKS5 server address. |
| `socksPort` | SOCKS5 server port; must be non-zero. |
| `bridgeListenAddress` | Local bridge listen address. |
| `bridgePort` | Local bridge port; must be non-zero. |
| `pinnedObjectDir` | Caller-selected directory for persistent BPF objects. |

`version` is accepted in generated configurations but is currently informational; the C parser does not branch on it.

### Optional runtime fields

| Field | Default / normalization |
| --- | --- |
| `cgroupPath` | `/sys/fs/cgroup`. |
| `enableIpv6` | `false`. |
| `enableDnsHijack` | `false`. |
| `debugStats` | `false`; also enabled by `BPF2SOCKS_DEBUG_STATS=1` or `true`. |
| `tokenIpv4Prefix` | `127.0.0.0/8`; must retain the supported `/8` token layout. |
| `tokenIpv6Prefix` | `fd7a:7374:6572:6973::/64`; must retain the supported `/64` token layout. |
| `workerCount` | `0` selects 4 workers on systems with at least 8 online CPUs, otherwise 2; capped at 8 and by session capacities. |
| `tcpBufferSize` | 65536 bytes; zero resets to the default. |
| `maxTcpSessions` | 4096; zero resets to the default, maximum 8192. |
| `tcpConnectTimeoutMilliseconds` | 10000; normalized to 1000–60000. |
| `tcpIdleTimeoutMilliseconds` | 300000; non-zero values normalized to 1000–3600000. Zero disables the idle timeout. |
| `udpSocketBufferSize` | 524288 bytes; zero resets to the default. |
| `udpBatchSize` | 10; zero resets to the default. |
| `maxUdpSessions` | 4096; zero resets to the default. |
| `maxUdpBindings` | 16384; raised to at least `maxUdpSessions`. |
| `udpIdleTimeoutSeconds` | 60; zero resets to the default. |
| `maxUdpPendingBytes` | 67108864; normalized to 131072–134217728. |
| `dnsTransactionTimeoutMilliseconds` | 60000; normalized to 1000–600000. |

### Policy fields

| Field | Description |
| --- | --- |
| `policy.mode` | `0` blacklist, `1` whitelist, `2` global; default `2`. |
| `policy.uids` | Selected UID list, up to 8192 entries. |
| `policy.bypassUids` | Forced bypass UID list, up to 8192 entries. |
| `policy.bypassDirectCidrs` | Load direct CIDR files and bypass matches. |
| `policy.directCidrPathV4` | IPv4 CIDR file used when direct bypass is enabled. |
| `policy.directCidrPathV6` | IPv6 CIDR file used when direct bypass and IPv6 are enabled. |
| `hotspotInterfacePrefixes` | Hotspot interface selectors, up to 64 entries. |
| `ignoredInterfaces` | Interfaces excluded from interception, up to 64 entries. |
| `proxyPrivateCidrsV4` / `proxyPrivateCidrsV6` | Private CIDRs explicitly proxied, up to 512 per family. |
| `bypassPrivateCidrsV4` / `bypassPrivateCidrsV6` | Private CIDRs explicitly bypassed, up to 512 per family. |

The bridge automatically bypasses its own effective GID so its SOCKS connections are not recaptured. This value is derived at runtime and is not a JSON field.

## Platform requirements and safety

Run as root with a mounted BPF filesystem, a usable cgroup hierarchy, the required BPF program/map/link types, and sufficient file-descriptor limits. The program attempts to raise `RLIMIT_NOFILE` to 65535 and further reduces configured capacities when the available limit is lower.

The configuration controls pinned objects, cgroup attachment, PID signaling, and traffic interception. Generate it from trusted application state and use an application-specific `pinnedObjectDir`.

## Parent-project integration

Mount the repository root directly at `bpf2socks/src/main/native`. The parent owns NDK discovery, ABI selection, compiler flags, packaging, and Gradle tasks.

## License

GPL-3.0. See [LICENSE](LICENSE).
