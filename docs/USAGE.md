# 使用说明

面向普通用户与进阶用户：先按界面操作，再给出命令行与排障参考。

## 一、快速上手（界面文字导览）

1. **第 1 步：连接 Shizuku**
   - 安装并打开 `ControllerStarRail`。首页会自动检查 Shizuku，并区分未安装、已安装未启动和待授权。
   - 按弹窗前往官方下载、打开 Shizuku 或申请授权；完成后可点「重新检查」。授权一次长期记住。
2. **第 2 步：连接并检测手柄**
   - REDMI 游戏手柄切到 PC/Xbox 模式并蓝牙配对；X5 Lite 使用深蓝灯原生 HID 模式，有线手柄按需开启 OTG。
   - Shizuku 就绪后 App 自动扫描，也可点「重新检测手柄」。列表会显示 USB 有线 / 蓝牙、VID:PID、建议 Profile 与扳机类型。
   - 点选要转发的设备；App 保存 VID:PID，重插后 event 编号变化也不影响选择。Profile 保持 `auto`。
3. **第 3 步：启动桥**
   - 检测到手柄后点主按钮「③ 启动手柄桥」。桥与虚拟手柄状态变绿，表示虚拟 Xbox360 手柄已创建。
   - 高级设置通常保持默认；「独占」用于避免双重输入，「开机自启」用于设备重启后恢复。
4. **进入游戏**
   - **完全退出《崩坏：星穹铁道》后重新进入**，再到设置 → 控制检查「切换控制模式」。
   - 小米游戏中心虚拟按键浮层若遮挡，可用 App 的「关闭浮层」。

**其它入口**：通知栏可快速启停；下拉快捷设置磁贴同样可一键开关；「日志」区域可折叠展开用于排障。

> 启动后桥独立常驻，直到设备重启前都不需要再开 Shizuku / 无线调试 / App。未 root 时设备重启后仍可能需要 Shizuku 重新启动一次。

## 二、命令行参数完整参考

进阶用户可经 Shizuku shell 直接调用 `uinput_x360`（释放后的路径以 `<二进制路径>` 占位表示）：

| 参数 | 说明 |
| --- | --- |
| `--list` | 枚举 `/dev/input/event*` 中被判定为手柄的设备，打印名称、VID:PID、轴/按键能力；不启动转发 |
| `--device <路径或ID>` | 指定物理输入设备；支持 `<event 节点>` 或 `3537:1116` 形式的 VID:PID，后者在重插后更稳定 |
| `--profile <名称>` | `auto` / `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`；默认 `auto`。0.3.0 起轴绑定按实际能力自动推断，Profile 主要影响按键布局与标签 |
| `--identity <名称>` | 虚拟设备身份：`xbox360`（默认）/ `xboxone` / `xboxbt` / `ds4` / `dualsense`。后三者为蓝牙总线，实验性 |
| `--trigger-mode <模式>` | `auto`（默认，自动识别薄膜扳机）/ `analog`（强制模拟量）/ `digital`（强制二值） |
| `--trigger-swap` | 交换 LT 与 RT（左右按反时开启） |
| `--trigger-invert` | 反转扳机极性（松开却满行程时开启） |
| `--legacy-mapping` | 回退到 0.2.x 的写死 Profile 轴表（逃生口，一般不需要） |
| `--config <文件>` | 加载自定义按键/轴映射文件；`axis FROM TO` 规则可覆盖自动轴绑定 |
| `--prefix <前缀>` | 指定 pid / log / status 文件前缀；单例与 `--stop` 依赖它 |
| `--no-grab` | 不使用 `EVIOCGRAB` 独占物理设备（调试用，可能出现双重输入） |
| `--detach` | fork + setsid 脱离终端与 App/Shizuku 生命周期独立常驻，stdio 全部重定向 |
| `--stop` | 按 `--prefix` 对应的 pid 文件终止已运行实例 |
| `--help` | 打印帮助并退出 |

占位示例：

```
<二进制路径> --help
<二进制路径> --list
<二进制路径> --profile auto --prefix <占位前缀> --detach
<二进制路径> --prefix <占位前缀> --stop
```

## 三、状态 JSON 字段

桥把运行状态写入 `--prefix` 指定的 status 文件，App 周期读取以刷新四路灯。字段值均为占位示意：

```json
{
  "state": "running",
  "pid": 12345,
  "profile": "auto",
  "physical": "REDMI Gaming Controller",
  "physical_path": "/dev/input/eventX",
  "virtual": "Microsoft X-Box 360 pad",
  "identity": "xbox360",
  "vid": "045e",
  "pid_hex": "028e",
  "bus": 3,
  "events": 123456,
  "uptime": 3600
}
```

| 字段 | 含义 |
| --- | --- |
| `state` | `starting` / `waiting` / `running` / `stopped` / `error` |
| `pid` | 守护进程 PID；App 会另用 `kill -0` 校验其存活 |
| `profile` | 当前生效的 Profile |
| `physical` / `physical_path` | 已连接物理手柄的名称与 event 节点 |
| `virtual` / `identity` | 虚拟设备名称与身份预设 key |
| `vid` / `pid_hex` / `bus` | 虚拟设备的 VID、PID 与总线类型（3 = USB，5 = 蓝牙） |
| `events` | 累计转发事件数 |
| `uptime` | 运行时长（秒） |

## 四、故障排查表

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| Shizuku 灯灰 | 未激活 / 未授权；无 root 重启后未重启 | 激活并授权；重启后重新启动一次 Shizuku |
| 桥进程灯灰 | `/dev/uinput` 不可用、ABI 二进制缺失、单例冲突 | 看日志；确认内核支持 uinput；`--stop` 后重试；确认 `assets/bin/<abi>/` 有二进制 |
| 物理手柄灯灰 | 未切 PC/Xbox 模式、未配对、被排除为触屏/传感器 | 重新配对并切模式；用 `--list` 确认被判定为手柄 |
| 虚拟设备灯灰 | uinput 创建失败、权限不足 | 确认经 Shizuku shell 执行；检查 status 的 `last_error` |
| 按键错乱 / 摇杆满偏 | 轴绑定不符预期 | 保持 `auto`；看日志 `bind ...` 表；必要时用 `--config` 覆盖或 `--legacy-mapping` 回退 |
| **LT / RT 按反、推右摇杆触发扳机** | 0.2.x 的 Profile 轴表左右写反、一个虚拟轴被多个物理源写入 | 升级 0.3.0；仍反则加 `--trigger-swap`；松开却满行程加 `--trigger-invert` |
| 扳机跳变 / 只有到底才生效 | 薄膜开关或轴缺 absinfo | `--trigger-mode digital`（0.3.0 默认 `auto` 已能自动识别）；日志会打印 `has no absinfo, using adaptive 0..255` |
| 双重输入 | 未独占物理设备 | 去掉 `--no-grab`，开启「独占」 |
| 热插拔后失效 | 设备节点变化 | 等自动重连；必要时重启桥 |
| 游戏仍无「切换控制模式」 | 虚拟设备 ID 不符、游戏缓存 | 核对 status `virtual` 为 `045e:028e`；重进游戏设置页 |
| 重启后桥未自动起 | 未 root 且 Shizuku 未自动启动 | 手动重启 Shizuku；满足可信 WLAN 条件可自动（详见 README） |

## 相关文档

[架构设计](ARCHITECTURE.md) · [兼容性](COMPATIBILITY.md) · [README](../README.md)
