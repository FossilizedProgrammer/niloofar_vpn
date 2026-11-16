# Niloofar VPN（尼洛法 VPN）

[English](README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · [Español](README.es.md) · **简体中文**

Niloofar VPN 是一款多内核 Android VPN 客户端，支持 Firefox、Aether、Psiphon、Sun and Lion、SSTP、Lantern、OpenRung 和 Tor。应用使用 Kotlin 与 Jetpack Compose 构建，支持 Android 8.0（API 26）及以上版本，提供 ARM32、ARM64 和通用 APK。

## 1.3.0 新增功能

- **OpenRung 内核**：将 Android 原生可执行文件 `libopenrung.so` 随应用打包，通过本地代理与公共 TUN 服务连接；无需单独安装 OpenRung 应用。
- **OpenRung 服务器选择**：与 Firefox、Lantern 和 SSTP 一样，可以查看服务器列表、测试可达性，并选择自动模式或固定服务器。
- **应用分流（Split tunneling）**：选择直接联网、不经过 VPN 的 Android 应用；所有内核共用同一份排除列表。
- **兼容旧版 Android 的选择窗口**：搜索时窗口会适应软键盘，应用列表可滚动，保存和取消按钮保持可用。
- 新增本简体中文说明文档。

## 使用方法

1. 安装与设备架构匹配的 APK；不确定时可使用通用版本。
2. 在设置中选择语言和内核。Firefox 需要登录 Mozilla 账户，其余内核无需该账户。
3. 选择 VPN 模式或代理模式。首次启动 VPN 模式时，允许 Android 的 VPN 连接请求。
4. 对支持服务器选择的内核，可以使用“自动”或在服务器列表中选择目标。
5. 点击连接按钮。实际可用性取决于当前网络、内核和服务器状态。

界面支持英语、波斯语、俄语、西班牙语和中文；波斯语使用从右到左布局。

### VPN 模式

应用通过 Android `VpnService` 创建系统 TUN 接口，再交给所选内核处理。普通应用无需单独设置代理；应用分流中选中的应用不进入此 VPN。客户端自身也被排除，以避免内核连接再次进入自己的隧道。

### 代理模式

不创建系统 VPN，仅提供本地代理。需要使用代理的应用必须自行配置代理地址：

- SOCKS5：默认 `127.0.0.1:1080`。
- HTTP：默认 `127.0.0.1:8080`。

端口可在设置中修改；以当前设置为准。应用分流不会影响代理模式，也不会覆盖应用自己配置的代理。

### 应用分流

打开 **设置 → 应用分流 → 选择排除的应用**：

- 按应用名称或包名搜索，勾选需要直接联网的应用。
- 支持同时选择多个应用；搜索和筛选不会清除已有选择。
- 点击“保存”保留修改，点击“取消”放弃本次修改。
- 点击“清除选择”后再保存，可恢复为不排除任何用户应用。
- **修改从下一次连接开始生效**，不会自动重启当前连接。

注意事项：

- 排除列表适用于所有内核，但仅在 VPN 模式下生效。
- Android 按 UID 应用规则；与所选应用共用 UID 的其他应用也可能被排除。
- 如果系统启用了“阻止不使用 VPN 的连接”，排除的应用可能无法联网，而不是改为直连。
- 已卸载的应用不会阻止 VPN 建立；保留的条目仍可在选择窗口中移除。
- 这是按应用排除，不是按域名或 IP 分流，也不是“仅允许选中应用使用 VPN”的模式。

### 与其他设备共享

Android 应用级 VPN 不会自动接管热点客户端的流量。使用网络共享功能时，另一台设备需要手动配置手机的局域网地址及设置中的 SOCKS5 或 HTTP 端口。

共享会使代理对局域网开放，只应在可信网络使用；不要将代理端口暴露到公共网络。它不等同于无需配置的热点 VPN。

## 构建

打包现有内核需要 JDK 17 或以上版本（CI 使用 JDK 21）、Android SDK Platform 35、Build Tools 35.0.0，以及 `readelf` 或 `llvm-readelf`。仓库包含 Gradle Wrapper、预编译内核和启动资源；常规 APK 构建不会重新编译所有内核。

在 `local.properties` 中设置本机 SDK 路径，或配置 `ANDROID_HOME`，然后执行：

```sh
./tools/build-apks.sh --check
./gradlew -DsocksProxyHost= testDebugUnitTest
./tools/build-apks.sh
```

如果依赖已完整缓存，也可以离线运行 Gradle：

```sh
./gradlew --offline -DsocksProxyHost= testDebugUnitTest assembleRelease
```

构建脚本会检查内核 ABI、必要入口点和 `tools/cores.manifest` 中的 SHA-256，构建签名 APK，验证签名，并复制到 `dist/`：

| 文件 | 架构 |
| --- | --- |
| `dist/NiloofarVPN-1.3.0-armeabi-v7a.apk` | 32 位 ARM |
| `dist/NiloofarVPN-1.3.0-arm64-v8a.apk` | 64 位 ARM |
| `dist/NiloofarVPN-1.3.0-universal.apk` | 包含以上两种架构 |

APK 不提交到 Git；预编译的原生内核保存在 `app/src/main/jniLibs/`。发布版本使用仓库现有的固定签名证书，以支持覆盖升级。早于 1.1.1 的旧 APK 可能使用不同证书，需要卸载后再安装；卸载会删除应用数据，请先自行备份需要保留的内容。

GitHub Actions 的 [构建工作流](.github/workflows/build-apks.yml) 在推送和拉取请求时构建 APK；推送 `main` 会更新预发布版 **Latest build**，推送 `v*` 标签会创建编号版本。每次运行也会提供 `NiloofarVPN-apks` 构建产物。

### 重新构建 OpenRung

重新编译原生内核还需要 Go、Android NDK 26.3.11579264 和 OpenRung 源码。当前构建脚本面向 Linux x86_64 主机：

```sh
OPENRUNG_SRC=/path/to/openrung ./tools/openrung/build-android.sh
./tools/build-apks.sh --update-manifest
./tools/build-apks.sh --check
./tools/build-apks.sh
```

脚本在临时目录中应用补丁，不修改上游源码目录；使用 Go 1.25.5、`with_utls` 和 Android cgo 构建 ARM32、ARM64 内核。补丁提供机器可读的中继列表，并修正 Android 非特权代理模式下的接口绑定问题。详情见 [OpenRung 构建说明](tools/openrung/README.md) 和 [内核升级指南](docs/core-upgrade.md)。

重新分发内核时应遵守各上游项目的许可证，并保留相应源码与补丁；OpenRung 使用 GPL-3.0-or-later。

## 主要目录

```text
app/src/main/java/com/ffvpn/client/
  App.kt                     注册并初始化 VPN 内核
  MainActivity.kt            主界面与系统权限处理
  Texts.kt                   多语言界面文本
  core/                      内核适配、设置、状态与服务器选择
  core/OpenRungCore.kt        OpenRung 进程与隧道桥接
  core/OpenRungServers.kt     中继列表、缓存和可达性测试
  service/TunnelVpnService.kt 公共 VPN 服务与应用排除规则
  ui/SettingsSheet.kt        设置界面
  ui/SplitTunnelPicker.kt    应用分流选择窗口
app/src/main/jniLibs/        各架构预编译原生内核
app/src/test/               本地单元测试
tools/build-apks.sh          检查、构建、验签与打包
tools/openrung/              OpenRung 构建脚本与补丁
```

## 已知限制与验证范围

- 目前提供 ARM32 和 ARM64，不提供 x86/x86_64 APK。
- 部分已有 ARM64 内核尚未满足 16 KB 内存页对齐要求；构建脚本会警告，不能据此保证所有内核兼容采用 16 KB 页的 Android 15+ 设备。
- 网络环境和服务端状态会影响连接；可达性测试结果不保证服务器始终可用。
- 应用分流已在 Android 8.1 实机上验证：被排除的 Chrome 使用直连出口，未排除的浏览器使用 VPN 出口；清除排除列表并重新连接后，Chrome 恢复使用 VPN 出口。该验证不代表已覆盖所有 Android 版本、内核或网络环境。
