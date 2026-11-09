# ControllerStarRail

> 一个 **Shizuku 插件**：让 Android 手机 / 平板上的《崩坏：星穹铁道》（`com.miHoYo.hkrpg`）识别到游戏手柄，在设置中出现「切换控制模式」。无需 root、不修改游戏、不需要网络权限。
>
> 0.3.0 起可切换虚拟设备身份（Xbox 360 / Xbox One / Xbox Wireless / DS4 / DualSense），用于**实验性**适配其它按型号白名单的手柄游戏；星铁请保持默认的 Xbox 360 身份。
>
> **前置条件：必须安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并授权本应用。** 插件自身没有创建虚拟输入设备的权限，全部能力来自 Shizuku 提供的 shell 权限；未安装、未激活或未授权时，本插件无法工作。

## 已实测可用的手柄

| 手柄 | 连接方式 / 模式 | 实测结论 |
| --- | --- | --- |
| REDMI 游戏手柄 | 蓝牙，PC / Xbox 模式 | v0.2.0 真机复验：启动桥后游戏出现「切换控制模式」，按键与摇杆可用 |
| 盖世小鸡 X5 Lite | Type-C 有线，Android 原生 HID 直连模式（深蓝指示灯） | v0.2.1 真机复验：桥接后星铁识别到手柄；`auto` 会自动选 `android` 布局 |

其它手柄（Xbox / PS / Switch Pro、其它 Type-C 有线手柄等）走的是同一条转发链路，也已内置对应 Profile，但**尚未逐一实测**；欢迎按 [兼容性说明](docs/COMPATIBILITY.md) 提交验证结果。

> 上表均已真机复验通过：虚拟设备被系统识别为 `GAMEPAD`，游戏正常出现「切换控制模式」。X5 Lite 需在桥启动后**完全退出星铁再重进**（游戏通常只在启动时枚举手柄）。其余手柄未逐一实测，遇到问题请附 `--list` 输出提 issue。

## 解决什么问题

《崩坏：星穹铁道》基于 Unity + InControl，按**设备名 / VID:PID 白名单**匹配手柄 profile，只认 `Microsoft X-Box 360 pad`、`Xbox Wireless Controller`（`045e:028e` 等）。很多手柄在系统里是正常可用的输入设备，但上报的名称或 ID 不在白名单中，游戏就不会给出手柄模式。

本插件借 **Shizuku** 提供的 shell 权限，把物理手柄「翻译」成一个标准的 Xbox 360 手柄，让游戏认为接上了受支持的手柄：不改游戏、不 root、不需要电脑常连。

补充两点：改蓝牙别名无效（别名不会落到输入设备上报的名称与 ID 上）；游戏中心类 App 的触控映射是另一条链路，与本方案无关。

## 前置依赖：Shizuku（必需）

**没有 Shizuku，这个插件什么都做不了。** Android 普通应用无法写 `/dev/uinput`，也就无法创建虚拟手柄；本项目是通过 Shizuku 的 `UserService` 借到 shell（ADB）权限，才能启动 `uinput_x360` 并创建 `045e:028e` 的虚拟 Xbox 360 手柄。无障碍服务、Device Owner、改蓝牙别名都无法替代这一步。

- 官方仓库与文档：<https://github.com/RikkaApps/Shizuku>（下载、激活方式、版本要求以该项目 README 为准）
- 本项目**不内置、不修改、不分发** Shizuku，请自行安装激活后对本应用授权；一次授权长期记住。
- 建议使用 **Shizuku 13.6.0+**。未 root 时，设备重启后 Shizuku 自身需要重新激活一次；Android 13+ 连接「可信 WLAN」时 13.6.0+ 可自动启动，满足条件即可全程自动恢复。
- 注意区分：桥**启动后**会脱离 Shizuku 独立常驻，直到设备重启前都不用再打开 Shizuku / 无线调试 / App；但「启动桥」与「重启后恢复」这两步始终依赖 Shizuku 授权。

## 工作原理

1. App 通过 Shizuku 的 `UserService` + AIDL，以 shell 权限执行静态编译的 `uinput_x360`。
2. native 程序扫描 `/dev/input/event*`，用 `EVIOCGNAME` / `EVIOCGID` / `EVIOCGBIT` 判定手柄，排除触屏、传感器与自身创建的虚拟设备。
3. 用 `/dev/uinput` 创建一个 `Microsoft X-Box 360 pad`：`BUS_USB`、vendor `0x045e`、product `0x028e`、version `0x0110`，带标准按键与轴。
4. 按 Profile 重映射事件，并依据 `EVIOCGABS` 的 min/max 把轴值线性缩放到虚拟轴范围后逐事件转发；`EVIOCGRAB` 独占物理设备，避免系统同时收到原始事件造成双重输入。
5. `--detach` 让桥进程脱离 App 与 Shizuku 生命周期独立常驻：虚拟设备保持存在、手柄热插拔自动重连、状态写入 JSON 文件供 App 读取。

## 功能特性

- 自动发现标准手柄，支持蓝牙 / 有线 / 接收器连接、热插拔自动重连和纯数字按键手柄；有线设备按 VID:PID 记住选择，重插后不受 event 编号变化影响。
- 内置 Profile：`auto` / `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`；`auto` 按设备身份或轴布局推断，也支持 `--config` 自定义映射。
- **身份预设**：`xbox360`（默认）/ `xboxone` / `xboxbt` / `ds4` / `dualsense`，为按型号白名单的游戏提供不同 VID:PID、名称与总线类型；启动日志会打印实际身份与轴绑定表。
- **扳机专项修复**：按实际轴能力自动绑定，一个虚拟轴只接一个物理源；支持 Android 模拟扳机及 `Z/RZ` 右摇杆 + 数字扳机布局，杜绝 LT/RT 与右摇杆串轴。
- App：「Shizuku → 手柄 → 启动桥」三步引导，Shizuku 未安装 / 未启动 / 未授权时提供对应操作；手柄列表显示连接方式、ID 与建议布局。
- 通知栏控制与快捷设置磁贴，不打开 App 也能启停。
- `BOOT_COMPLETED` 接收者 + 短暂前台服务轮询重试，在 Shizuku 可用后自动恢复桥。
- 三架构静态二进制（`arm64-v8a` / `armeabi-v7a` / `x86_64`），按 `Build.SUPPORTED_ABIS` 自动选择。
- Gradle 工程 + GitHub Actions CI；不申请网络权限、不收集数据。

## 使用方式

### 下载

- 项目与源码：<https://github.com/1caru3zz3/ControllerStarRail>
- 最新安装包：[Releases 页面](https://github.com/1caru3zz3/ControllerStarRail/releases) 或仓库内的 [releases/ControllerStarRail-v0.3.0.apk](releases/ControllerStarRail-v0.3.0.apk)（0.3.0 与 0.2.x 同签名，可直接覆盖安装）
- **必需前置**：[Shizuku 官方仓库](https://github.com/RikkaApps/Shizuku)（自行安装激活，本 APK 不包含）
- 直接覆盖安装即可（debug 签名，请勿当作正式分发版本）；问题反馈请到 [Issues](https://github.com/1caru3zz3/ControllerStarRail/issues)。

### 准备

| 项目 | 要求 |
| --- | --- |
| 设备 | Android 手机 / 平板，内核提供可写的 `/dev/uinput` |
| **Shizuku（必需）** | 建议 13.6.0+，自行安装激活并授权本应用（一次授权长期记住）；[官方仓库与文档](https://github.com/RikkaApps/Shizuku) |
| 手柄 | 已连接并处于标准 HID 手柄模式；有线手柄需开启 OTG、避免触屏映射模式 |
| 其它 | **不需要 root**；不需要网络权限 |

### 步骤

1. 从 [Releases](https://github.com/1caru3zz3/ControllerStarRail/releases) 安装本插件 APK（包名 `com.controllerstar.x360bridge`）并打开。
2. 按首页第 1 步检查 Shizuku：未安装时可前往官方下载，已安装未启动时可直接打开，运行后按弹窗授权本应用。
3. 连接手柄，等首页第 2 步显示已检测到；未出现时点「重新检测手柄」查看模式与 OTG 提示。选中你的手柄，Profile 保持 `auto`。
4. 点「③ 启动手柄桥」，桥与虚拟手柄状态变绿后即可退出 App。
5. **完全退出星铁再重进**，进入设置 → 控制，应出现「切换控制模式」。

> 若系统有游戏中心类的虚拟按键浮层遮挡操作，可在 App 里点「关闭浮层」（等价于 `am force-stop com.xiaomi.gamecenter`）。

### 日常控制

- 通知栏按钮 / 快捷设置磁贴可直接启停，无需打开 App。
- 设置项：映射方案、开机后自动恢复、独占物理手柄、启动时关闭浮层。
- 日志区可展开查看 native 输出，定位设备发现与 Profile 选择问题。

### 重启后的行为

- 桥一旦启动便独立常驻，**直到设备重启前都不需要再开 Shizuku、无线调试或 App**，Shizuku 服务停止也不影响桥。
- 未 root 时设备重启后仍可能需要 Shizuku 重新启动一次，这是 Android 的系统限制；App 会在 Shizuku 可用后自动把桥拉起。
- Shizuku 13.6.0+ 在 Android 13+ 连接「可信 WLAN」时可无 root 自动启动，满足条件即可全程自动恢复。
- 100% 开机无感只有 root / Magisk 模块一条路，本项目不采用。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| Shizuku 灯不亮 | 确认已安装 / 激活并授权；未 root 时重启后需重新启动一次 Shizuku；[官方仓库](https://github.com/RikkaApps/Shizuku) |
| 能不能不装 Shizuku | **不能**。创建 `/dev/uinput` 虚拟设备需要 shell / root 权限，详见 [前置依赖](#前置依赖shizuku必需) |
| 桥进程灯不亮 | 展开日志查看；确认 `/dev/uinput` 可用、对应 ABI 二进制存在 |
| 手柄列表为空 | 确认手柄处于标准 HID 模式（有线手柄开 OTG）；用 `--list` 检查是否生成 event 节点 |
| 游戏仍不出现「切换控制模式」 | **完全退出星铁后重新进入**（Unity / InControl 通常在启动时枚举手柄）；核对虚拟设备为 `045e:028e`；排查其它手柄抢占 |
| 状态显示运行中但游戏无手柄 | 桥进程可能已被系统回收；新版会校验 pid 并如实显示「未运行」，点启动即可恢复 |
| 按键错乱 / 摇杆幅度不对 | 保持 `auto`；在 App 设置里查看日志 `bind LX/LY/RX/RY/LT/RT` 绑定表，必要时用 `--config` 覆盖 |
| **LT / RT 按反，或推右摇杆触发扳机** | 轴绑定串了（0.2.x 的已知问题） | 升级 0.3.0 后保持 `auto`；仍反则开「交换 LT / RT」；松开却满行程则开「反转扳机极性」 |
| 数字扳机按了没反应 / 跳变 | 薄膜开关或按键式扳机 | 0.3.0 已自动二值化并支持 `BTN_TL2` / `BTN_TR2`；可在设置里把扳机模式改为「强制二值」 |
| 切换身份后游戏仍不认 | 游戏可能校验系统蓝牙配对列表 | 属实验性功能；原神 / 鸣潮的攻克路径见 [兼容性说明](docs/COMPATIBILITY.md) |
| 玩原神 / 绝区零 / 终末地要不要开桥 | 这三款是通用检测或按 VID:PID 判定，REDMI 手柄**原生模式**很可能直接被认 | **先关桥**再进游戏；开桥会独占物理手柄并多出一个虚拟手柄，反而容易双重输入。只有星铁这类按设备名白名单的游戏才需要桥，详见 [兼容性说明](docs/COMPATIBILITY.md) 第 8 节 |

**为什么不能像 GKD 那样完全免 Shizuku？** GKD 依赖无障碍服务，而创建 `/dev/uinput` 虚拟输入设备只有 shell / root 权限能做到；Dhizuku 的 Device Owner 只暴露设备管理 API，也不提供任意 shell 执行。二者都不适用于本方案，项目不宣称 100% 免 Shizuku。

更多细节见 [使用说明](docs/USAGE.md)、[架构设计](docs/ARCHITECTURE.md)、[兼容性说明](docs/COMPATIBILITY.md)。

## 项目结构

```
ControllerStarRail/
├── app/src/main/java/...                  # 纯 Java UI、Shizuku 服务、广播接收者、前台服务
├── app/src/main/aidl/...                 # 与桥交互的 AIDL 接口
├── app/src/main/assets/bin/<abi>/uinput_x360  # 各 ABI 静态二进制
├── native/uinput_x360.c                  # 发现 / Profile / 缩放 / 转发 / 守护 / 状态
├── native/CMakeLists.txt
├── scripts/                              # 一键构建与 native 重建脚本
├── .github/workflows/                    # GitHub Actions CI
├── docs/                                 # ARCHITECTURE / USAGE / COMPATIBILITY
├── releases/                             # 可直接下载的安装包
└── build.gradle / settings.gradle / gradle.properties
```

## 从源码构建

```bash
./gradlew assembleRelease                 # 或：bash scripts/build.sh（等价封装）
ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-native.sh   # 改动 C 源码后重建三架构二进制
```

需要 JDK 17 与 Android SDK 35；native 二进制随仓库提供，仅在改动 C 源码时才需要 NDK r26d。CI 会校验 native 可编译并产出 release APK（使用 debug 签名，请勿用于正式分发）。

## 致谢

- **[Shizuku](https://github.com/RikkaApps/Shizuku)**（RikkaApps）：本项目**唯一的权限来源**，提供无 root 的 shell 权限通道；没有它就无法创建虚拟手柄。请优先阅读其官方文档与许可。
- **InControl**：Unity 手柄输入库（白名单机制的背景）。
- **Linux uinput**：内核虚拟输入设备能力。

## 许可

[MIT](LICENSE) © 2026 ControllerStarRail contributors。
