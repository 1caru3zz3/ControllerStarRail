# 使用说明

面向普通用户与进阶用户：先按界面操作，再给出命令行与排障参考。

## 一、快速上手（界面文字导览）

1. **安装与授权**
   - 安装 `ControllerStarRail` APK 并打开。状态卡片第一行「Shizuku」灯灰色表示未就绪。
   - 按 Shizuku 官方方式激活并授权一次（一次授权长期记住），灯变绿。
2. **连接手柄**
   - REDMI 游戏手柄切到 PC/Xbox 模式并蓝牙配对。
   - 状态卡片第三行「物理手柄」灯变绿，下方「已发现手柄」列表出现条目；点选要转发的设备。
3. **选择 Profile**
   - 在「Profile」下拉选 `auto`（默认自动判定）；按键错位时手动选 `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`。
   - 进阶用户可用 `--config` 自定义映射（见第二节）。
4. **一键启动**
   - 点主开关「启动桥」。第二行「桥进程」与第四行「虚拟设备」灯依次变绿，表示虚拟 Xbox360 手柄已创建。
   - 开启「独占」（对应 `EVIOCGRAB`）避免物理与虚拟双重输入；「开机自启」决定是否注册开机拉起。
5. **进入游戏**
   - 打开《崩坏：星穹铁道》→ 设置 → 控制，应出现「切换控制模式」，按提示切换。
   - 小米游戏中心虚拟按键浮层若遮挡，可用 `am force-stop com.xiaomi.gamecenter` 关闭。

**其它入口**：通知栏可快速启停；下拉快捷设置磁贴同样可一键开关；「日志」区域可折叠展开用于排障。

> 启动后桥独立常驻，直到设备重启前都不需要再开 Shizuku / 无线调试 / App。未 root 时设备重启后仍可能需要 Shizuku 重新启动一次。

## 二、命令行参数完整参考

进阶用户可经 Shizuku shell 直接调用 `uinput_x360`（释放后的路径以 `<二进制路径>` 占位表示）：

| 参数 | 说明 |
| --- | --- |
| `--list` | 枚举 `/dev/input/event*` 中被判定为手柄的设备，打印名称、VID:PID、轴/按键能力；不启动转发 |
| `--device <路径>` | 指定物理输入设备（如 `<event 节点>`），跳过自动发现 |
| `--profile <名称>` | `auto` / `redmi` / `xbox` / `ps` / `android` / `switch` / `generic`；默认 `auto` |
| `--config <文件>` | 加载自定义按键/轴映射文件，覆盖内置 Profile |
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
  "running": true,
  "pid": "<占位 pid>",
  "profile": "auto",
  "physical": {
    "name": "REDMI Gaming Controller",
    "vid": "045e",
    "pid": "0b13",
    "event": "<event 节点>",
    "grabbed": true
  },
  "virtual": {
    "name": "Microsoft X-Box 360 pad",
    "bus": "BUS_USB",
    "vendor": "045e",
    "product": "028e",
    "version": "0110"
  },
  "uptime_sec": 3600,
  "last_error": ""
}
```

| 字段 | 含义 |
| --- | --- |
| `running` | 桥主循环是否运行 |
| `pid` | 守护进程 PID（占位示意） |
| `profile` | 当前生效的 Profile |
| `physical` | 物理手柄：名称、VID:PID、event 节点、是否独占 |
| `virtual` | 虚拟设备参数，应与 `045e:028e` 一致 |
| `uptime_sec` | 运行时长（秒） |
| `last_error` | 最近一次错误描述，空串表示无错误 |

## 四、故障排查表

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| Shizuku 灯灰 | 未激活 / 未授权；无 root 重启后未重启 | 激活并授权；重启后重新启动一次 Shizuku |
| 桥进程灯灰 | `/dev/uinput` 不可用、ABI 二进制缺失、单例冲突 | 看日志；确认内核支持 uinput；`--stop` 后重试；确认 `assets/bin/<abi>/` 有二进制 |
| 物理手柄灯灰 | 未切 PC/Xbox 模式、未配对、被排除为触屏/传感器 | 重新配对并切模式；用 `--list` 确认被判定为手柄 |
| 虚拟设备灯灰 | uinput 创建失败、权限不足 | 确认经 Shizuku shell 执行；检查 status 的 `last_error` |
| 按键错乱 / 摇杆满偏 | Profile 选错、轴未按 min/max 缩放 | 换 `--profile`；用 `--config` 自定义映射 |
| 双重输入 | 未独占物理设备 | 去掉 `--no-grab`，开启「独占」 |
| 热插拔后失效 | 设备节点变化 | 等自动重连；必要时重启桥 |
| 游戏仍无「切换控制模式」 | 虚拟设备 ID 不符、游戏缓存 | 核对 status `virtual` 为 `045e:028e`；重进游戏设置页 |
| 重启后桥未自动起 | 未 root 且 Shizuku 未自动启动 | 手动重启 Shizuku；满足可信 WLAN 条件可自动（详见 README） |

## 相关文档

[架构设计](ARCHITECTURE.md) · [兼容性](COMPATIBILITY.md) · [README](../README.md)
