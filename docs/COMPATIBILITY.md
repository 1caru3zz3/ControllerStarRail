# 兼容性说明

## 1. 设备与系统要求

| 项目 | 要求 |
| --- | --- |
| 目标设备 | Redmi K Pad（其它设备满足条件亦可尝试） |
| 目标游戏 | 《崩坏：星穹铁道》`com.miHoYo.hkrpg` |
| Android 版本 | 建议 Android 10 及以上；自启动例外条件需 Android 13+ |
| CPU ABI | `arm64-v8a` / `armeabi-v7a` / `x86_64`，按 `Build.SUPPORTED_ABIS` 选二进制 |
| Shizuku | 13.6.0+（自动启动例外）；更旧版本可用但需手动启动 |
| 内核 `uinput` | 必须存在且可写 `/dev/uinput`；多数官方内核默认启用，个别定制内核可能裁剪 |
| root | **不需要**；本项目不 root、不使用 Magisk 模块 |
| 网络权限 | 不需要；App 不申请网络权限、不收集数据 |

**uinput 可用性自检**（经 Shizuku shell，路径以占位符表示）：

```
<二进制路径> --list
ls -l /dev/uinput
```

若 `/dev/uinput` 不存在或无写权限，虚拟设备无法创建，桥进程灯不会变绿。

## 2. Shizuku 与自启动条件

- 一次授权长期记住；**未 root 时设备重启后仍可能需要 Shizuku 重新启动一次**（官方手册要求）。
- 例外：Shizuku 13.6.0+ 在 Android 13+、连接「可信 WLAN」时可无 root 自动启动，此时配合开机自启接收者可在重启后自动拉起桥。
- 启动后桥独立常驻，直到设备重启前都不需要再开 Shizuku / 无线调试 / App。
- GKD 依赖无障碍服务、无法创建 `/dev/uinput`；Dhizuku 的 Device Owner 不提供任意 shell 执行。二者均不适用于本项目。

## 3. 内置 Profile 与已知手柄

| Profile | 适用 / 选择依据 | 已知手柄 |
| --- | --- | --- |
| `auto` | 默认；按设备名 / VID:PID 或轴布局自动判定 | 下列全部设备 |
| `redmi` | 设备名 `REDMI Gaming Controller` / `045e:0b13` 一类 | REDMI 游戏手柄（PC/Xbox 模式，本项目主要目标） |
| `xbox` | 标准 Xbox 按键/轴布局 | Xbox 360 / One / Series、雷神 G35（对照组，上报 `Xbox Wireless Controller`） |
| `ps` | DualShock/DualSense 布局 | DualShock 4、DualSense |
| `android` | 扳机在 `ABS_GAS` / `ABS_BRAKE`，右摇杆在 `ABS_Z` / `ABS_RZ` | 部分国产手柄的 Android 布局 |
| `switch` | Switch 布局（按键序与 Xbox 不同） | Switch Pro 控制器 |
| `generic` | 兜底：仅做基本按键/轴转发 | 未列入的通用手柄 |

> 雷神 G35 作为对照组可直接被游戏识别，常用于验证虚拟设备路径是否正确工作。Xbox 系手柄本身已在白名单内，一般无需本项目转发。

`--list` 输出中的 `profile=` 列是 `auto` 模式会选择的 Profile，`axes=` 列列出设备支持的轴，`triggers=` 列区分模拟/数字扳机，可用于快速诊断。厂商名（如 GameSir / BETOP / Flydigi）不会被硬套某个 Profile——同品牌不同型号的轴布局差异很大，一律按实际轴能力推断。

## 4. Type-C 有线手柄（以盖世小鸡 X5 Lite 为例）

桥**不区分物理连接方式**：只要手柄在系统中生成标准的 `/dev/input/event*` 游戏手柄节点，就能被 `--list` 发现并转发；虚拟设备始终是 USB 的 `Microsoft X-Box 360 pad`（`045e:028e`），游戏侧与蓝牙手柄完全一致。因此有线 USB 手柄、2.4G 接收器手柄、蓝牙手柄走的是同一条路径。

| 条件 | 说明 |
| --- | --- |
| 模式 | 必须处于 **Android 原生 HID 直连模式**（X5 Lite 指示灯为深蓝）。浅蓝的 G-Touch / V-Touch 是触屏映射模式，产生的是触摸事件而非标准手柄事件，本项目无法转发；红色为 Switch 模式 |
| OTG | 部分小米 / HyperOS 设备需在设置中开启 OTG；手柄由设备 Type-C 口供电，无内置电池，支持充电透传 |
| 节点 | `--list` 能看到该设备且能读到 `EV_KEY` 与摇杆轴；若系统只生成触屏或厂商私有节点则不支持 |
| 物理 | X5 Lite 夹持范围 105–213 mm、Type-C 插头可上下微调；8.8 英寸级平板接近上限，需实测能否夹稳、接口是否对齐、有无保护壳干涉 |
| 扳机 | X5 Lite 为数字式薄膜扳机，`--list` 会显示 `triggers=digital`；若游戏读取模拟轴，LT/RT 可能表现为普通按键或无效，需按 `getevent` 结果定制映射 |

操作步骤：插好并开启 OTG → 打开 App →「刷新设备列表」→ 选中该手柄 → Profile 先用 `auto`（可参考 `--list` 的 `profile=` 建议）→ 启动桥 → 进游戏设置检查「切换控制模式」。若右摇杆/扳机错乱，依次尝试 `xbox`、`android`、`generic`；若扳机完全无效，把 `--list` 与 `getevent -pl` 输出提交 issue，可补充「按键→轴」映射（当前 `--config` 仅支持轴→轴、键→键）。

> 若手柄本身已被游戏直接识别（上报名称含 Xbox 等白名单关键字），无需启动本桥；否则会同时存在物理与虚拟两个手柄。

## 5. 提交新手柄的 getevent 信息

若某手柄在 `auto` 下按键错乱或未被发现，请附以下信息提交 issue（用占位符，勿贴真实序列号 / 个人信息）：

1. 设备名与 VID:PID：`<二进制路径> --list` 的输出。
2. 能力位与轴范围：

```
getevent -il <event 节点>      # 名称、ID、EV_KEY/EV_ABS 能力
getevent -p  <event 节点>      # 各轴 min/max/fuzz/flat/resolution
```

3. 原始事件样本：轻按各按键、推动各摇杆与扳机各一次，`getevent -lt <event 节点>` 的若干行输出。
4. 期望的按键/轴含义（A/B/X/Y、左摇杆 X/Y、LT/RT、D-pad 等）。
5. 手柄固件/模式说明（PC / Xbox / Android 模式）与系统版本。

维护者据此补充内置 Profile 或给出 `--config` 映射。提交前请先脱敏，勿包含配对码、设备地址、账号等。

## 6. 已知限制

- 未 root 且 Shizuku 未自动启动时，设备重启后仍需手动重启一次 Shizuku；本项目不宣称 100% 免 Shizuku。
- 震动、LED、陀螺仪等反向/扩展能力不转发，仅转发标准按键与轴。
- `EVIOCGRAB` 独占后物理手柄不再向系统派发原始事件；调试用 `--no-grab` 会导致双重输入。
- 个别厂商内核若裁剪 `uinput` 或限制 `/dev/uinput` 写权限，本方案不可用。
- 游戏更新可能调整 InControl 白名单；若「切换控制模式」消失，请核对虚拟设备仍为 `045e:028e`。
- 小米游戏中心虚拟按键浮层与触控映射可能干扰操作，可用 `am force-stop com.xiaomi.gamecenter` 关闭。
- 蓝牙别名修改、小米游戏中心识别均无效（已验证并放弃）。
- 数字扳机手柄的 LT/RT 可能只有 0 / 满值两态；`--config` 目前不支持把按键映射成轴事件。

## 相关文档

[架构设计](ARCHITECTURE.md) · [使用说明](USAGE.md) · [贡献指南](../CONTRIBUTING.md)
