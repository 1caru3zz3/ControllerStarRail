# 贡献指南

感谢参与 ControllerStarRail。项目已在 [GitHub](https://github.com/1caru3zz3/ControllerStarRail) 开源，欢迎到 [Issues](https://github.com/1caru3zz3/ControllerStarRail/issues) 反馈问题、提交 PR 与新手柄适配。

## 1. 提交 issue

请先搜索已有 issue。提交时注明：

- 设备型号、Android 版本、Shizuku 版本、手柄型号与模式（PC/Xbox/Android 等）。
- App 版本（见 CHANGELOG）、是否 root、是否连接可信 WLAN。
- 复现步骤、期望结果与实际结果。
- 状态卡片四路灯情况、可折叠日志、`--list` 输出、status JSON 的 `last_error`。
- **脱敏**：不要贴真实设备 IP、配对码、无线调试端口、本地绝对路径、账号或个人信息，一律用占位符（如 `<设备地址>`、`<端口>`、`<event 节点>`）。

## 2. 提交 PR

- 一个 PR 聚焦一件事；较大改动先开 issue 讨论。
- 仅改文档时请勿触碰 `native/`、`app/` 下的源码及构建目录；源码改动请附构建验证结果。
- native 改动需说明对应模块（发现 / Profile / 缩放 / 转发 / 守护 / 单例 / 状态）与参数影响。
- 新增 Profile 或命令行参数要同步更新 `docs/COMPATIBILITY.md`、`docs/USAGE.md`、`README.md`。
- 提交信息用简体中文或英文，简述「改了什么 + 为什么」。

## 3. 提交新手柄 Profile 需要的信息

除 issue 基础信息外，请附（均已脱敏、用占位符）：

1. `<二进制路径> --list` 输出：设备名、VID:PID、EV_KEY/EV_ABS 能力。
2. `getevent -il <event 节点>`：名称、ID、能力位。
3. `getevent -p <event 节点>`：各轴 min/max/fuzz/flat/resolution。
4. `getevent -lt <event 节点>`：轻按各键、推动各摇杆与扳机的原始事件样本。
5. 按键/轴含义对照（A/B/X/Y、左/右摇杆 X/Y、LT/RT、D-pad 等）。
6. 建议的 Profile 名与选择依据（设备名 / VID:PID / 轴布局）。

若不便直接改代码，也可只给一份 `--config` 自定义映射文件内容，由维护者整理进内置 Profile。

## 4. 代码风格

- **C（native）**：遵循现有 `native/` 风格；简洁、少依赖、静态编译；错误路径必须清理 fd 与 uinput 设备；新增 option 同步 `--help` 与文档。
- **Java（App）**：纯 Java、无 AndroidX；沿用包 `com.controllerstar.x360bridge`；UI 逻辑与 Shizuku 调用分层，AIDL 接口变更需双向兼容。
- **文档**：简体中文（LICENSE / .gitignore 除外），术语与 CLI 参数保持原文；不夸大能力、不写入任何真实环境信息。
- **脚本 / CI**：`scripts/` 与 `.github/workflows/` 保持可复现，密钥用占位配置，禁止提交 `*.jks` / `*.keystore`。

## 5. 构建验证步骤

```
./gradlew assembleDebug          # 本地调试构建
./gradlew assembleRelease        # release 构建（使用 assets/bin/<abi>/ 中已提供的二进制）
bash scripts/build.sh            # 等价封装
ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-native.sh   # 改动 C 源码后重建三架构二进制
```

提交前请确认：

- 三种 ABI（`arm64-v8a` / `armeabi-v7a` / `x86_64`）的 `assets/bin/<abi>/uinput_x360` 均产出。
- `--list` 能枚举手柄、`--detach` 能常驻、`--stop` 能终止、status JSON 字段完整。
- 在目标设备上走一遍 README「快速开始」，确认游戏出现「切换控制模式」。
- GitHub Actions CI 通过。
- `git status` 无构建产物、密钥、日志或 APK 被意外纳入（见 `.gitignore`）。

## 相关文档

[README](README.md) · [架构设计](docs/ARCHITECTURE.md) · [使用说明](docs/USAGE.md) · [兼容性](docs/COMPATIBILITY.md)
