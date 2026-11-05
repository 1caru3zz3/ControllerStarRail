# ControllerStarRail

让 Redmi K Pad 上的《崩坏：星穹铁道》（`com.miHoYo.hkrpg`）识别 REDMI 游戏手柄，使游戏设置中出现「切换控制模式」。

> 纯 Java（无 AndroidX）App + 静态编译的 native 程序，无需 root、无需网络权限、不收集数据。

## 项目定位

星穹铁道基于 Unity + InControl，按设备名 / VID:PID 白名单匹配手柄 profile，只认 `Microsoft X-Box 360 pad`、`Xbox Wireless Controller`（`045e:028e` 等）。REDMI 游戏手柄在蓝牙 PC/Xbox 模式下上报为 `REDMI Gaming Controller`（`045e:0b13` 一类），虽是合法输入设备，却不在白名单中，游戏因此不识别。

已验证无效的尝试：修改蓝牙别名；依赖小米游戏中心识别（该方向已放弃）。对照组雷神 G35（上报为 `Xbox Wireless Controller`）可正常识别，印证了白名单是根因。

本项目不修改游戏、不需要 root：借 **Shizuku** 的 shell 权限运行静态编译的 `uinput_x360`，把物理手柄事件转发到伪装成标准 Xbox 360 手柄的虚拟 `/dev/uinput` 设备，让游戏白名单命中。

## 原理简述

1. App 通过 Shizuku 的 `UserService` + AIDL 以 shell 权限执行 `uinput_x360`。
2. native 程序扫描 `/dev/input/event*`，按 `EVIOCGNAME` / `EVIOCGID` / `EVIOCGBIT` 判定手柄，排除触屏、传感器与自身虚拟设备。
3. 用 `/dev/uinput` 创建标准 Xbox 360 手柄：`BUS_USB`、vendor `0x045e`、product `0x028e`、version `0x0110`，标准按键与轴。
4. 以 `EVIOCGRAB` 独占物理设备避免双重输入，按 Profile 重映射事件、按 `EVIOCGABS` 的 min/max 线性缩放轴值后逐事件转发。
5. 进程脱离 App 与 Shizuku 生命周期独立常驻，虚拟设备不掉线；状态写 JSON 文件供 App 读取。

## 功能特性

- 通用手柄自动发现与热插拔自动重连，虚拟设备常驻不掉线。
- 内置 Profile：`auto` / `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`，支持 `--config` 自定义映射。
- 按设备名 / VID:PID 或轴布局自动选择 Profile；轴值线性缩放到虚拟轴范围。
- `--detach`（fork + setsid）独立常驻，stdio 全部重定向避免占住 Shizuku exec 管道；pid 文件 + flock 保证单例。
- App：状态卡片四路灯、一键启停、已发现手柄列表与选择、Profile 选择、开机自启与独占开关、可折叠日志。
- 通知栏控制、快捷设置磁贴、`BOOT_COMPLETED` 接收者 + 短暂前台服务轮询拉起桥。
- 按 `Build.SUPPORTED_ABIS` 从 `assets/bin/<abi>/uinput_x360` 选二进制，面向 `arm64-v8a` / `armeabi-v7a` / `x86_64`。
- Gradle（AGP）+ GitHub Actions CI；native 二进制随仓库提供，可用 NDK clang / CMake 脚本重建。

## 快速开始

1. **安装 APK**：从 Releases 安装 `ControllerStarRail`（包名 `com.controllerstar.x360bridge`）。
2. **授权 Shizuku**：安装并启动 Shizuku，按官方方式激活一次；在 App 内授予权限（一次授权长期记住）。
3. **连接手柄**：REDMI 游戏手柄切到 PC/Xbox 模式并通过蓝牙配对。
4. **一键启动**：打开 App，等状态卡片四路灯变绿，点主开关启动桥；可先在手柄列表确认已发现设备、选好 Profile。
5. **进星铁**：进入《崩坏：星穹铁道》→ 设置 → 控制，应出现「切换控制模式」，按提示切换即可。

> 小米游戏中心的虚拟按键浮层可能遮挡操作，可用 `am force-stop com.xiaomi.gamecenter` 关闭。

## 自启动与重启后的说明

- **桥一旦启动便独立常驻**：`--detach` 后进程脱离 App 与 Shizuku，直到设备重启前都不需要再开 Shizuku / 无线调试 / App。
- **未 root 时设备重启后仍可能需要 Shizuku 重新启动一次**：官方 Shizuku 手册明确，无 root 时每次重启都需要重新启动 Shizuku 本身。
- **例外**：Shizuku 13.6.0+ 在 Android 13+、连接「可信 WLAN」时，可无 root 自动启动 Shizuku；此时配合开机自启可在重启后自动拉起桥。条件不满足仍需手动重启一次。
- **100% 开机无感只有 root / Magisk 模块一条路**，本项目不采用、不 root。

### 简短 FAQ

- **GKD 能不能免 Shizuku 实现？** 不能。GKD 依赖无障碍服务，无法创建 `/dev/uinput` 设备，其模式不适用于本项目。
- **Dhizuku 呢？** 同样不适用。Device Owner 只暴露设备管理 API，不提供任意 shell 执行。
- **为什么改蓝牙别名没用？** 游戏按设备名 / VID:PID 白名单判定，别名不落到输入设备上报的名称与 ID 上。
- **需要网络吗？** App 不申请网络权限，不收集、不上传任何数据。

## 目录结构

v2 Gradle 工程化后的目标结构：

```
ControllerStarRail/
├── app/
│   ├── src/main/java/...                  # 纯 Java UI、Shizuku UserService、AIDL 调用、广播接收者、前台服务
│   ├── src/main/aidl/...                 # 与桥交互的 AIDL 接口
│   ├── src/main/assets/bin/<abi>/uinput_x360  # 各 ABI 静态二进制
│   ├── src/main/AndroidManifest.xml
│   └── build.gradle                      # AGP 构建，assets/bin/<abi>/ 为预编译二进制
├── native/
│   ├── uinput_x360.c                     # 发现 / Profile / 缩放 / 转发 / 守护 / 单例 / 状态
│   └── CMakeLists.txt
├── scripts/                              # 可复现的手工构建脚本
├── .github/workflows/                    # GitHub Actions CI
├── docs/                                 # ARCHITECTURE / USAGE / COMPATIBILITY
├── build.gradle / settings.gradle / gradle.properties
├── README.md / CHANGELOG.md / CONTRIBUTING.md / LICENSE / .gitignore
```

## 从源码构建

```bash
./gradlew assembleRelease                 # 或：bash scripts/build.sh（等价封装）
ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-native.sh   # 可选：重建三架构 native 二进制
```

- 需要 JDK 17 与 Android SDK 35；native 二进制已随仓库提供（`app/src/main/assets/bin/<abi>/`），只在改动 C 源码时才需要 NDK r26d 重建。
- CI 在 GitHub Actions 里校验 native 可编译并产出 release APK；产物为已签名 APK（release 使用 debug 签名，请勿用于正式分发）。

## 支持的手柄 / 设备

- 目标设备：Redmi K Pad；目标游戏：《崩坏：星穹铁道》。
- 主要适配：REDMI 游戏手柄（PC/Xbox 模式）。
- 兼容：Xbox 360 / One / Series、雷神 G35（对照组）、DualShock / DualSense、Switch Pro、通用手柄。
- ABI：`arm64-v8a` / `armeabi-v7a` / `x86_64`。详见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| Shizuku 灯不亮 | 确认已激活并授权；无 root 重启后需重新启动一次 Shizuku |
| 桥进程灯不亮 | 看可折叠日志；确认 `/dev/uinput` 可用、对应 ABI 二进制存在 |
| 物理手柄灯不亮 | 确认手柄为 PC/Xbox 模式并已配对；用 `--list` 检查是否被发现 |
| 游戏仍不出现「切换控制模式」 | 核对虚拟设备为 `045e:028e`；重进游戏设置页；排查其它手柄抢占 |
| 按键错乱 / 轴范围不对 | 换 Profile 或用 `--config` 自定义映射；轴值按物理 min/max 线性缩放 |

## 致谢

- **Shizuku**：提供无 root 的 shell 权限通道。
- **InControl**：Unity 手柄输入库（白名单机制的背景）。
- **Linux uinput**：内核虚拟输入设备能力。

## 许可

[MIT](LICENSE) © 2026 ControllerStarRail contributors。
