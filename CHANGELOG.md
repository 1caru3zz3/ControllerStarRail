# 更新日志

本项目遵循语义化版本，日期格式为 YYYY-MM-DD。

## 0.2.0 — 2026-11-05

本次为通用化重构（v2 架构）。

### 新增

- **通用手柄自动发现**：扫描 `/dev/input/event*`，按 `EVIOCGNAME` / `EVIOCGID` / `EVIOCGBIT` 判定，排除触屏、传感器与自身虚拟设备。
- **Profile 体系**：`auto` / `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`，按设备名 / VID:PID 或轴布局自动选择，支持 `--config` 自定义映射。
- **轴线性缩放**：依据物理 `EVIOCGABS` 的 min/max 映射到虚拟 Xbox360 轴范围。
- **独立常驻**：`--detach`（fork + setsid）脱离 App 与 Shizuku 生命周期，stdio 全重定向；pid 文件 + flock 单例；热插拔自动重连、虚拟设备不掉线。
- **状态 JSON**：供 App 读取的四路灯状态与错误信息。
- **完整参数**：`--list` / `--device` / `--profile` / `--config` / `--prefix` / `--no-grab` / `--detach` / `--stop` / `--help`；`EVIOCGRAB` 独占。
- **App 新 UI**：状态卡片四路灯、一键启停、已发现手柄列表与选择、Profile 选择、开机自启与独占开关、可折叠日志。
- **开机自启**：`BOOT_COMPLETED` 接收者 + 短暂前台服务轮询拉起；通知栏控制与快捷设置磁贴。
- **多 ABI**：按 `Build.SUPPORTED_ABIS` 从 `assets/bin/<abi>/uinput_x360` 选二进制（`arm64-v8a` / `armeabi-v7a` / `x86_64`）。
- **工程化**：Gradle（AGP）构建、CMake/NDK 脚本重建 native、GitHub Actions CI、`scripts/` 一键构建脚本。

### 说明

- 未 root 时设备重启后仍可能需要 Shizuku 重新启动一次；启动后桥独立常驻，直到设备重启前都不需要再开 Shizuku / 无线调试 / App。

## 0.1.0 — 2026-11-03

初版，验证方案可行性。

- 单手柄转发：把 REDMI 游戏手柄事件转发为 `Microsoft X-Box 360 pad`（`045e:028e`）虚拟设备。
- 借 Shizuku shell 权限运行静态编译的 `uinput_x360`，使《崩坏：星穹铁道》出现「切换控制模式」。
- 手工构建：`aidl` → `javac` → `d8` → `aapt2` → `zipalign` → `apksigner`。
- App 为四按钮简易界面（启动 / 停止 / 列表 / 关于），无 Profile、无常驻、无自启。

### 已知不足

- 仅支持单一固定手柄、无轴缩放、无独立常驻、无开机自启、无 CI，均已在 0.2.0 解决。

## 版本对比

| 能力 | 0.1.0 | 0.2.0 |
| --- | --- | --- |
| 手柄支持 | 单一固定 REDMI 手柄 | 通用自动发现 + 7 个 Profile |
| 轴处理 | 原样转发 | 按 min/max 线性缩放 |
| 进程生命周期 | 跟随 App / Shizuku | `--detach` 独立常驻 |
| 单例与热插拔 | 无 | pid + flock、自动重连 |
| 开机自启 | 无 | 广播接收者 + 前台服务轮询 |
| UI | 四按钮 | 状态卡片四路灯 + 列表 + 日志 |
| 构建 | 手工链路 | Gradle + NDK 脚本 + CI + scripts |

## 升级提示

- 从 0.1.0 升级无需迁移数据；首次启动会重新释放对应 ABI 的 `uinput_x360` 二进制。
- 升级后建议重新执行一次「一键启动」，并确认状态卡片四路灯全绿。
- 升级后原有的开机自启、独占、Profile 偏好建议在新 UI 中重新确认一次。
