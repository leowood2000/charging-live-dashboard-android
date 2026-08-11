# 充电实时仪表盘（Android）

直接在 Redmi K80 Pro（miro）及同类 MIUI/HyperOS root 机型上读取 sysfs、MCA 日志和 mi_thermald 状态的充电仪表盘。应用使用原生 WebView 展示，不需要 ADB、电脑或网络权限。

当前版本：**v0.11.18（versionCode 65）**。

Web / ADB 版见 [charging-live-dashboard](https://github.com/leowood2000/charging-live-dashboard)。两版的数据语义保持一致。

## v0.11.18 重点改进

- 投票日志改为按 topic 增量合并，某个低频 topic 滑出日志窗口时不再让有线/无线限制值瞬间消失。
- 无线会话边界会清理旧无线票；有线断开边界会清理旧有线票，避免跨会话残留。
- 新增独立 `derived.wireless_path`，无线 CP/Buck 路径、比例、Quick Wireless Final、ICL 和 RX 上限不再依赖 `wireless_buck_input` topic 是否仍在当前日志窗口。

## v0.11.17 重点改进

- 修复有线充满停充后仍显示 `5mA · CP ibus_total` 的语义问题：CP 总线近空闲且 `chg_enable=OFF`、电池电流接近 0 时，输入主测量切换为 USB `CURRENT_NOW`。
- 页面同时显示“外部系统输入”和独立“CP支路电流”，明确区分手机系统耗电与电池充电电流。
- 正常 CP 充电或停充但 CP 仍有明显电流时，继续保留 `CP ibus_total` 为主输入。

## v0.11.16 重点改进

- Android 温度与热控卡片改为与 Web 一致的紧凑布局，充电时不再强制占满整行。
- 新增 `wireless_connected` 会话锁存：`power_good_on/off` 决定充电板连接，低 RX 电流只影响 `input_source`，不再让停充旁路的当前限制卡反复消失。
- 新增连接状态回归测试，覆盖低电流旁路、断开后残留 vout 和未知状态回退。

## v0.11.15 重点改进

- 修复有线 CP 切换无线 CP 后偶发显示旧有线 `CP 1:1`：`USB ONLINE=0` 现在硬否决有线，残留 VBUS 不再把页面锁进有线分支。
- 输入源判定改为 USB ONLINE 三态规则；仅 ONLINE 未知时允许 VBUS 回退，USB 在线且无线也有信号时仍以有线为准。
- `ibus_total` 只在输入源确定后解释为当前 CP 总线数据，不再反向证明有线存在；新增 5 个固定回归测试。

## v0.11.14 重点改进

### 更低的应用自身功耗

- App 仅在前台采集；进入后台或锁屏时暂停 root 读取、WebView 和 JavaScript 定时器，回到前台再立即刷新。
- 快速数据每 3 秒采集一次；MCA 日志在连接/充电时约 10 秒、完全断开时约 60 秒采集一次。
- 每轮 sysfs/battery/thermal 合并为一次 root 命令；投票、会话和功率路径日志也合并为一次 root 命令。
- root 命令串行执行，避免多个 `su`、`tail`、`grep` 同时抢占 CPU；首次 `onResume` 不再重复触发一轮立即采集。
- session/event 最多读取最近两个轮转文件，只解析并保留最新一个会话；功率路径使用最新一个文件的 1 MiB 窗口。
- 使用 `grep -F -e` 固定字符串白名单和手机端 `tail` 截断，降低日志扫描、传输和 Java 解析成本。
- 已移除不需要的 `INTERNET` 权限。

> Android App 前台与 Web 页面同时显示时，两套采集器会分别执行 root/sysfs/日志读取，CPU 唤醒会叠加。实机 20 秒短测中，Android 单独前台约 13.97% 总忙碌率，同时开启 Web 活跃采集约 14.91%（仅作方向性参考）。做功耗测试时只保留一个前台界面：看 Web 时把 Android App 置于后台；看 Android 时关闭 Web 页面或停止服务。

### 更紧凑、层次更清楚的界面

- 实时数据压缩为输入功率、电池功率、电池电流、电池电压、电池温度和 SOC。
- “当前限制”固定顺序：充电路径最先；当前电池充电电流上限与实际电池电流组成一组；无线 CP 显示 RX 允许上限与实际 RX，Buck/旁路显示无线输入 ICL 与实际 RX；温度、场景和使能状态随后展示。
- 电池上限尚未捕获时保留 `-- / 待捕获`，避免数据缺失时整项消失。
- 私有快充、无线策略、有线策略和实时会话档案默认折叠。
- 四条曲线依次为：电池电流、输入电流、输入电压、输入功率。
- 电流投票只优先展示生效主题和生效票，未生效票收进折叠区；顺序优先体现电池充电限制与无线输入限制。

### 已校正的数据语义

- 实时数据中的“电池温度”是电芯实体温度；“当前限制”中的绿色“虚拟温度”是 mi_thermald 的主要温控决策温度，并与当前热控场景放在一起。
- 无线路径改为实时 `mca_platform_cp/ibus_total` 与当前会话 `operation mode/work_mode` 融合判定：`≥100mA` 为 CP、`20–100mA` 为切换中；`≤20mA` 只有在没有当前会话 CP 证据时才判 Buck，既不把预启动 3–5mA 当成 CP，也不会因 CP 稳态瞬时读到 0 而误判 Buck。
- mi_thermald 仍只读取 64KB 尾部，但单次提取“最新无线行 + 最新通用虚拟温度行”并缓存最后有效值；空闲启动无需先充电即可显示虚拟温度和热控场景。
- 会话档案把连续的 `open path ibus` 电流爬升合并成一条“CP 建链”，保留首末电流和次数，不再误导为反复开关快充路径。
- 输入仍连接但已自动停充时，路径从“停止中”稳定收敛到“已停止”，当前上限不再回显旧 CP/Buck 目标；有线连接必须由实时 USB 在线或有效 VBUS 证明，拔线后不会被缓存日志重新判成有线。
- 日志年龄采用事件真实时间，并正确处理单个日志文件跨午夜；重启采集器不会把旧决策误标成“刚刚”。
- 当前电池充电电流上限按来源与路径取值：无线 CP 使用手机原生 Quick Wireless `cur_max:[Final]`；有线 CP 使用当前 `div1/div2/div4` 路径的 `mca_thermal` 上限；Buck 使用 `buck_charge_curr effective`。路径或 single/multi 拓扑不能唯一确定时显示“待确认”。
- 从有线切换到无线时，实时输入源优先于残留 USB ONLINE/有线 CP 日志，避免无线慢充沿用上一段有线 CP 路径。
- 无线 CP 总览显示 `RX 输出允许上限` 与 `实际 RX 输出` 两行；两行分别使用与电池上限/实际电流相同的主次高亮颜色。
- 无线停充但仍在旁路供电时，保留当前会话的 `无线输入 ICL（旁路）`；电池充电上限显示为 `-- / 已停止`。
- 无线 Buck/旁路输入 ICL 取 `wireless_buck_input effective`，属于上游平台策略；无线 CP 的 RX 允许上限取当前会话 `rx_iout_limit`；实际 RX 输出取 `wls_debug iout`，属于遥测。各字段处于不同控制域，不做数值一致性判断。
- `wireless_qc=100` 表示策略修正/下降量语义，不能解释为“最终输入限流为 100mA”，也不能作为最终无线输入 ICL。
- `xm_wls` 是能力/适配器允许值，不等同当前仲裁 winner。
- `rx_iout_limit` 是驱动策略层的 RX 允许上限，和实际 RX 输出、上游 ICL 是不同层级。

## 截图

![v0.11.9 实机首页](screenshots/dashboard-v0.11.9.png)

## 安装

1. 从 [Releases](https://github.com/leowood2000/charging-live-dashboard-android/releases) 下载 `charging-live-dashboard-android-v0.11.17.apk`。
2. 安装并打开应用。
3. 在 KernelSU / Magisk 中授予 root 权限。

应用启动后自动开始采集。没有 root 权限或数据读取失败时，页面会显示占位符/错误状态，不生成模拟数据。

## 从源码构建

要求：JDK 17、Android SDK 34、Gradle。

```sh
gradle assembleDebug
```

默认产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 主要数据来源

- `/sys/devices/platform/soc/soc:mca_*`：充电框架 sysfs
- `/sys/class/power_supply/battery/uevent`：电池状态、实体温度、电流和电压
- `/data/vendor/bsplog/charge/charge_logger/mca_log/`：MCA 投票、会话和功率路径日志
- `/data/vendor/thermal/thermal.dump`：虚拟温度、场景和热控等级

## 关键实现文件

- `SnapshotCollector.java`：采集、日志窗口、投票/会话解析、路径判定、缓存和 JSON 快照
- `RootShell.java`：root 探测、串行命令执行、超时和输出消费
- `MainActivity.java`：前后台生命周期、快慢采集调度和 WebView bridge
- `app/src/main/assets/index.html`：仪表盘 UI
- `MAINTENANCE_NOTES.md`：跨会话维护总结、已确认语义、踩坑记录和发布检查表

## 发布包校验

Release 同时提供 APK 与 `SHA256SUMS.txt`。安装前可对 APK 计算 SHA-256，并与校验文件比对。
