# 兼容性说明

## 1. 设备与系统要求

| 项目 | 要求 |
| --- | --- |
| 目标设备 | Android 手机 / 平板（满足下列条件的设备均可尝试） |
| 目标游戏 | 默认《崩坏：星穹铁道》`com.miHoYo.hkrpg`；0.3.0 起可切换虚拟身份，**实验性**适配其它按型号白名单的手柄游戏（原神 / 鸣潮等，尚未真机复验） |
| Android 版本 | 已在 Android 15 类设备验证；静态二进制以 API 34 sysroot 编译，更低版本未回归；自启动例外条件需 Android 13+ |
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
| `xbox` | 标准 Xbox 按键/轴布局 | Xbox 360 / One / Series、其它上报 `Xbox Wireless Controller` 的标准手柄 |
| `ps` | DualShock/DualSense 布局 | DualShock 4、DualSense |
| `android` | 扳机在 `ABS_GAS` / `ABS_BRAKE`，右摇杆在 `ABS_Z` / `ABS_RZ` | 部分国产手柄的 Android 布局 |
| `switch` | Switch 布局（按键序与 Xbox 不同） | Switch Pro 控制器 |
| `generic` | 兜底：仅做基本按键/轴转发 | 未列入的通用手柄 |

> 上报为 `Xbox Wireless Controller` 等白名单名称的手柄通常能被游戏直接识别，无需本项目转发；这类设备也适合作为验证虚拟设备路径的对照。
>
> 已真机复验：REDMI 游戏手柄（蓝牙 PC / Xbox 模式）在 v0.2.0 下完整跑通——桥创建的虚拟设备被系统标记为 `GAMEPAD`，游戏设置出现「切换控制模式」。

`--list` 输出中的 `profile=` 列是 `auto` 模式会选择的 Profile，`axes=` 列列出设备支持的轴，`triggers=` 列区分模拟/数字扳机，可用于快速诊断。厂商名（如 GameSir / BETOP / Flydigi）不会被硬套某个 Profile——同品牌不同型号的轴布局差异很大，一律按实际轴能力推断。

扫描会排除带 `INPUT_PROP_DIRECT` 属性的设备（触摸屏、手写笔、数位板）以及名称含 Pen / Stylus / Capacitive 的输入设备；这类设备常带 `BTN_JOYSTICK` 与 X/Y 轴，早期版本会误判成手柄并错误地 `EVIOCGRAB` 独占，导致触控笔失灵。

## 4. Type-C 有线手柄（以盖世小鸡 X5 Lite 为例）

桥**不区分物理连接方式**：只要手柄在系统中生成标准的 `/dev/input/event*` 游戏手柄节点，就能被 `--list` 发现并转发；虚拟设备始终是 USB 的 `Microsoft X-Box 360 pad`（`045e:028e`），游戏侧与蓝牙手柄完全一致。因此有线 USB 手柄、2.4G 接收器手柄、蓝牙手柄走的是同一条路径。

| 条件 | 说明 |
| --- | --- |
| 模式 | 必须处于 **Android 原生 HID 直连模式**（X5 Lite 指示灯为深蓝）。浅蓝的 G-Touch / V-Touch 是触屏映射模式，产生的是触摸事件而非标准手柄事件，本项目无法转发；红色为 Switch 模式 |
| OTG | 部分小米 / HyperOS 设备需在设置中开启 OTG；手柄由设备 Type-C 口供电，无内置电池，支持充电透传 |
| 节点 | `--list` 能看到该设备且能读到 `EV_KEY` 与摇杆轴；若系统只生成触屏或厂商私有节点则不支持 |
| 物理 | X5 Lite 夹持范围 105–213 mm、Type-C 插头可上下微调；8.8 英寸级平板接近上限，需实测能否夹稳、接口是否对齐、有无保护壳干涉 |
| 扳机 | 物理为薄膜开关，但设备实际上报 `ABS_GAS` / `ABS_BRAKE` 轴（0..255），`--list` 显示 `triggers=analog`；实测多为 0 / 满值两态，不是连续模拟量 |

实测（GameSir-X5 Lite，VID:PID `3537:1116`）：`ABS_X/Y/Z/RZ/GAS/BRAKE` 均为 0..255、`ABS_HAT0X/Y` 为 -1..1，标准 `BTN_GAMEPAD` 按键齐全；`auto` 会选 `android` 布局（Z/RZ → 右摇杆，GAS/BRAKE → 扳机），轴值自动缩放到虚拟设备量程，无需手动改 Profile。

操作步骤：插好并开启 OTG → 打开 App →「刷新设备列表」→ 选中该手柄 → Profile 先用 `auto`（可参考 `--list` 的 `profile=` 建议）→ 启动桥 → **完全退出星铁后重新进入**，再检查设置里的「切换控制模式」。0.3.0 起 `auto` 会按实际轴能力自动绑定并打印 `bind LT` / `bind RT` 表，一般**无需手动切换 Profile**；若 LT / RT 左右按反，在 App 设置里开「交换 LT / RT」，若松开却满行程则开「反转扳机极性」。按键式扳机（`BTN_TL2` / `BTN_TR2`）已自动转为模拟轴。仍异常时附 `--list`、`getevent -pl` 与日志中的绑定表提交 issue（`--config` 目前仅支持轴→轴、键→键）。

> 桥启动后虚拟设备才出现。若游戏在桥启动之前就已运行，Unity / InControl 一般不会动态重新枚举手柄，必须重启游戏；这是「桥已运行但游戏不显示」最常见的原因。

> 已真机复验通过：X5 Lite 经本桥转发后，星铁正常识别手柄。注意桥启动后需完全退出游戏再重进。若手柄本身已被游戏直接识别（上报名称含 Xbox 等白名单关键字），无需启动本桥；否则会同时存在物理与虚拟两个手柄。

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
- native 为静态链接并以 API 34 sysroot 编译（ARM64 Bionic 要求 `PT_TLS` 至少 64 字节对齐，低版本 sysroot 会直接被 loader 拒绝）；低于 Android 14 的系统未回归验证。
- 游戏更新可能调整 InControl 白名单；若「切换控制模式」消失，请核对虚拟设备仍为 `045e:028e`。
- 小米游戏中心虚拟按键浮层与触控映射可能干扰操作，可用 `am force-stop com.xiaomi.gamecenter` 关闭。
- 蓝牙别名修改、小米游戏中心识别均无效（已验证并放弃）。
- 部分手柄的 LT/RT 虽上报为模拟轴，物理却是开关（0 / 满值两态）；0.3.0 会自动二值化，必要时可用 `--trigger-mode digital` 强制。
- 非 `xbox360` 身份为实验性：切换 VID:PID 后 Android 可能选用不同的 `.kl` 按键布局，且游戏若额外校验系统蓝牙配对列表，单靠 `BUS_BLUETOOTH` 仍可能被拒绝。
- 震动（`EV_FF`）、LED、陀螺仪与按前台 App 自动切换身份仍未实现。
- 脱离 App 的 native 进程仍可能被系统回收；状态文件会残留上一次的 `running` / `waiting`。App 会同时校验 pid 存活状态，如实显示「未运行」，点一次启动即可恢复。

## 7. 轴绑定与扳机（0.3.0）

桥在连接物理手柄时读取 `EVIOCGBIT` 与 `EVIOCGABS`，为每个虚拟语义绑定**唯一**一个物理来源；未绑定轴直接丢弃，不再做 passthrough 猜测。日志中的 `bind ...` 表是排查第一入口。

| 物理轴能力 | LT | RT | 右摇杆 | 典型设备 |
| --- | --- | --- | --- | --- |
| `X/Y/Z/RZ/GAS/BRAKE` + HAT | `BRAKE` | `GAS` | `Z/RZ` | REDMI 游戏手柄、盖世小鸡 X5 Lite |
| `X/Y/RX/RY/Z/RZ` + HAT | `Z` | `RZ` | `RX/RY` | 标准 Xbox 360 / One、DS4、DualSense |
| `X/Y/RX/RY` + `BTN_TL2/TR2` | `BTN_TL2`（转模拟轴） | `BTN_TR2`（转模拟轴） | `RX/RY` | 只有数字扳机的手柄 |
| `X/Y/Z/RZ` + `BTN_TL2/TR2` | `BTN_TL2`（转模拟轴） | `BTN_TR2`（转模拟轴） | `Z/RZ` | 0.4.0 新增识别的 Android 数字扳机布局 |

0.4.0 起也允许只有标准手柄按键、没有 `EV_ABS` 的纯数字设备进入列表。物理手柄断开时会复位全部虚拟按键与轴，避免拔线或休眠后按键卡住。

判定遵循 Linux gamepad 规范与 AOSP `Vendor_045e_Product_028e.kl`：`BRAKE` = 左扳机、`GAS` = 右扳机；虚拟 `ABS_Z` = LTRIGGER、`ABS_RZ` = RTRIGGER。0.2.x 的 `redmi` / `android` Profile 把这两者写反，是「LT/RT 错位」的主因。

扳机还做三件修正：有符号轴的静止点按 0 而非 `minimum` 计算（避免常半按）；缺 `EVIOCGABS` 时用 0..255 兜底并按运行时观测值扩展量程（避免只有四分之一行程）；运行时取值数 ≤3 时自动二值化，>3 自动退回模拟量。

## 8. 其它游戏：先别开桥

2026-11-07 联网核实了「原神 / 绝区零 / 终末地都能识别 REDMI 手柄」的社区说法。**结论：对这三款游戏，本项目的桥很可能不需要，甚至有害；桥的价值仍然只在星穹铁道这类按设备名白名单的游戏上。**

### 8.1 REDMI 手柄有两种模式，社区说法混了两者

小米官方上手视频的设置页截图明确了两个模式：

- **映射模式**：「手柄按键模拟触屏功能，开启后可自定义按键坐标，**适用于所有游戏**」——本质是模拟点击，游戏并没有识别到手柄。部分评测里的「原神能玩」走的是这条路（游戏侧边栏工具箱 →「手柄按键」）。
- **原生模式**：「开启后可直接操控游戏，**适用于本身支持手柄的游戏**」——这才是真正的原生手柄输入。

REDMI 手柄仅蓝牙连接（官方视频未见 USB 接口），恰好满足原神「必须蓝牙」的要求。

### 8.2 三款游戏的判定方式与星铁不同

| 游戏 | 判定方式 | 依据 | REDMI 手柄原生模式 |
| --- | --- | --- | --- |
| 绝区零 | **通用检测**：设置 → 输入 → UI 布局 → 控制器 | Android Authority「any controller commonly used for mobile gaming should be compatible」；IT之家 2024 实测连手柄才出现手柄 UI 选项 | 大概率直接可用 |
| 明日方舟：终末地 | **通用检测**：登录界面「任意手柄操作」自动切手柄 UI | 鹰角官方 FAQ（endfield.hypergryph.com/news/8563）；支持范围虽列 PS/Xbox，但措辞是「其他品牌**可能存在部分兼容性问题**」而非拒绝；同类项目已实测用虚拟 Xbox360 让 iPega 手柄开启原生模式 | 大概率直接可用 |
| 原神 | **按 VID:PID / 已知型号**，安卓仅蓝牙 | HoYoverse 官方支持页仍只列 DualSense / DS4 / Xbox Wireless / Xbox Elite 2，并写明「不在列表即不获官方支持」；但 233乐园 2026-07 实测明确写「**原神：原生模式直连**」，另有第三方手柄原生识别的社区反馈 | 很可能可用，但非官方保证 |
| 崩坏：星穹铁道 | **InControl 设备名字符串白名单** | 本项目真机验证 | ❌ 仍需桥 |

**为什么同一个手柄结果不同**：REDMI 手柄在原生 / PC-Xbox 模式下上报 `045e:0b13`，而这个 VID:PID 正是 **Xbox Series X|S / Xbox One 蓝牙控制器**的标准 ID（SDL_GameControllerDB、OpenEmu issue、ESP32 HID 描述符均可查到），原神若按 VID:PID 判定就会当成「Xbox Wireless Controller」命中白名单；但星铁按 `Input.GetJoystickNames()` 的字符串匹配，REDMI 上报的是 `REDMI Gaming Controller`，不匹配。**本项目存在的理由正是这个差异。**

### 8.3 修正后的验证顺序（先不开桥）

1. 手柄切到 **原生模式**，关闭小米游戏中心的「手柄按键 / 映射」浮层。
2. **停止本项目的桥**（App 里点停止）。开桥会 `EVIOCGRAB` 独占物理手柄并额外创建一个虚拟手柄，系统里同时出现两个手柄，反而容易造成双重输入或游戏选错设备。
3. 完全退出游戏后重进，看**设置里是否出现「控制类型 → 手柄」/「UI 布局 → 控制器」，按键提示是否变成 A/B/X/Y 图标**。出现 = 真·原生识别；只是按键有反应但 UI 仍是触屏 = 映射模式，不算识别。
4. 三款都不认时，才回到 0.3.0 的身份预设：依次试 `xboxbt`（`045e:0b13` / `BUS_BLUETOOTH`）、`ds4`、`dualsense`，每次重启桥并完全重进游戏。
5. 换 VID:PID 后需核对设备上 `/system/usr/keylayout/Vendor_<vid>_Product_<pid>.kl` 的轴/键规则（`8bitdo-xbox-bridge` 踩过 `045e:02fd` 把 View 键映射成 `KEY_BACK` 的坑）。若游戏还查 `BluetoothAdapter` 配对列表或 `InputDevice.getDescriptor()`，则要走 `/system/bin/hid` + UHID 描述符（参考 `kishi-controller-bridge` 的 `XboxProfile.REGISTRATION`），这仍是未实现的下一阶段。

> 《鸣潮》官方 1.4 / 3.5 公告同样只列「无线连接的 Xbox 系列 / DS4 / DualSense / DualSense Edge」，尚未找到 REDMI 手柄的实测反馈，第 4 步的身份预设主要留给它和未来白名单变动。
>
> 风险：米哈游 / 库洛 / 鹰角的用户协议对「模拟用户操作 / 改变操作环境 / 伪造设备信息」有限制。本项目只做设备层兼容，不做宏、连点或自动化；实验性身份请自行评估账号风险。

## 相关文档

[架构设计](ARCHITECTURE.md) · [使用说明](USAGE.md) · [贡献指南](../CONTRIBUTING.md)
