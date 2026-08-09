# 充电实时仪表盘（Android）

K80 Pro（及同类 MIUI/HyperOS root 机型）无线/有线充电实时仪表盘 APK。

直接在手机上通过 root（KernelSU / Magisk）读取 sysfs、MCA 内核日志与 mi_thermald 状态，
**不需要 ADB、不需要电脑**。页面为 WebView 内嵌仪表盘，首页即展示实时 KPI 与**投票总仲裁结果**。

## 截图

| 首页实时 KPI | 无线/有线策略实时 | 投票与限流 | 总仲裁结果 |
|---|---|---|---|
| <img src="screenshots/01-top-kpi.png" width="230"> | <img src="screenshots/02-strategy-realtime.png" width="230"> | <img src="screenshots/03-vote-cp.png" width="230"> | <img src="screenshots/04-arbitration.png" width="230"> |

截图为 K80 Pro（miro）实机画面：首页 KPI、私有快充协商、无线/有线策略实时、MCA 投票与总仲裁结果（RX 输出电流上限 / 实测 RX 输出 / 电池充电电流上限）。

## 功能

- 实时 KPI：无线输入功率、电池充电功率/电流/电压、SOC、电池温度
- 电流符号统一：充电为正、放电为负，电池功率正负同理
- 私有快充协商（quick_charge_type / power_max / EPP 协商状态）
- 无线/有线热控实时数据（无线热控限流、有线热控等级、热控档位投票）
- 电流投票实时表 + **总仲裁结果**（生效客户、最终值、推算值）
- MCA 仲裁展示修正：驱动 `effective vote is now` 优先；`wls_icl` 经反编译确认为 **ADSP GLINK prop 0x1003** 下发的无线输入限流，详情卡单独标注，不再与 RX 输出电流混为一谈
- 每个 votable 标注仲裁类型（大部分来自 .ko 反汇编核实；`buck_charge_curr` 为项目假设 `MIN_ASSUMED`，仅详情卡“参考推算”），未知类型不做盲目推算
- 投票解析支持 `voting on/off`，并按主题分别缓存最近变动与结果，避免日志交错串线
- 投票区只显示**生效主题**（有启用票或驱动已给出实际结果）
- `wireless_auth_*`（20w/30w/50w/80w/voice_box/magnet）与 `wireless_bpp/bppqc2/bppqc3/epp_in` 是按充电板型号/协议模式预置的热控表，无论连接哪台垫都会被整批投票，已直接隐藏
- `term_volt` / `term_curr`（JEITA 终止电压/电流）合并为一张“JEITA 终止参数”卡并标注**静态常数**（由温度档决定，会话内基本不变）
- 有线/无线快充禁用卡按当前连接动态显示（无线充电只显示无线侧，有线充电只显示有线侧）
- 仲裁结果带**有效性校验**：主题全部撤票（如拔掉充电）后，旧 `effective vote is now` 不再展示，卡片按“生效主题”隐藏，避免拔电后仍挂着旧限流值
- 无线功率路径判定：quick wireless `work_mode=1/2/4` 是 CP 硬证据（本会话捕获后持续持有，窗口滚动不失效），`operation mode>0` 作交叉验证、`operation mode=0` 明确切 Buck（并清旧 work_mode），均无 → 待确认；首页显示“当前功率路径”（电荷泵 · 1:1/2:1/4:1 / Buck 直充）
- “当前功率路径”chip 附带电荷泵转换比（由 quick wireless work_mode 映射：1:1 bypass / 2:1 div2 / 4:1 div4）
- 未充电（battery STATUS ≠ Charging）时隐藏全部投票/仲裁卡片，仅保留生效场景、虚拟温度、电池温度与 JEITA 静态参数，避免“没充电还挂着一堆票”
- `wireless_buck_input` 固定放在详情卡：MCA 仲裁（effective 赢家）+ ADSP 无线 ICL（prop 0x1003）+ `xm_wls` 能力票 + 说明（已下发 ADSP，闭源固件如何应用不可见，不等同 RX 输出电流上限），不再进入首页总仲裁
- 总仲裁无线输入侧只显示可物理解释的限制：**RX 输出电流上限**（`strategy_class_wireless_op_get_rx_iout_limit` 按充电器类型/模式查表，允许上限）+ **实际 RX 输出电流**（wls_debug iout）；`wireless_buck_input` / `wls_icl` / `xm_wls` / `wireless_qc` 不进总仲裁
- `rx_iout_limit` 随无线会话保持：`power_good_on` 捕获、会话内持续有效，`work_mode`（1:1/2:1/4:1）切换与日志窗口滚动不失效，`power_good_off` 清空；会话日志读取失败时保留值并标 stale
- 无线平台输入 ICL：总仲裁上游层取 `wireless_buck_input` effective（ADSP prop 0x1003，上游策略，非 Buck 专属）；EPP+/QC 下与 RX iout 映射未闭环、不做数值比较；`soc_limit` 为 effective winner + SmartEndura 上下文 + ibat≈0 时标“当前上游限制”
- 无线电池侧上限按当前功率路径选择：CP 生效 → 总览只显示 quick wireless `cur_max:[Final]`（算法决策，带年龄/历史值标注），不单独展示“无线热控上限”（`wireless_sw_thermal_ich` effective 与本轮 `sw_thermal_ichg` 不等价，热控输入在 Quick Wireless 决策卡内看）；Buck 生效 → `buck_charge_curr` effective + 无线热控上限取 `wireless_thermal_XXw`；多票显示待确认、不做 max 猜测；路径未确认 → 显示“待确认”，不用 Buck FCC 冒充当前上限；`buck_charge_curr` 在 CP 下标注“Buck 路径 FCC”
- 新增只读卡“Quick Wireless 电池电流决策”：展示 select_max_ibat 的五个输入（channel_cur / temp_max_cur / tx_adapter_max / sw_qc_ichg / sw_thermal_ichg，标注当前瓶颈）、`cur_max:[Final]` 与实际 ibat，明确标注“算法聚合 · 非 MCA votable”
- CP 状态按会话解析：遇到 `power_good_on/off` 重置，只保留当前会话内的 sc8581 模式/分压比/cur_max，避免上一会话残留冒充当前值（如换垫后 6V Buck 不再误显示 2:1）
- 功率路径三态显示：cp（本会话 work_mode=1/2/4 或 operation mode>0，附分压比）/ buck（本会话明确 operation mode=0）/ 待确认（本会话尚无路径证据）
- 有线功率路径正式接入：会话边界（`usb online` / `real_type changed` / `power_good`）内按时间顺序取最后一次 `sc8581 operation mode` 判定 cp/buck；`quickchg work_mode` 与 `map_ibus_to_fsw ratio` 提供分压比；`cur_work_cp` 作交叉证据；输出 `derived.wired_cp`
- 有线 CP 激活时只显示对应比例的 div 卡（4:1 → div4_single/div4_multi）+ single/multi_chg_cur + thermal_flip；Buck/未知时隐藏全部 div，保留 buck_input / buck_charge_curr / chg_enable / quick_chg_disable / input_voltage / smartchg_delta_ichg / JEITA；`buck_5v/9v_*` 档位表与 `wireless_*` 始终隐藏
- 日志抓取白名单补齐有线 quickchg 信号（`update_work_mode_para` / `map_ibus_to_fsw` / `mca_quick_charge_select_max_ibat` / `select_cur_work_mode`）与有线会话边界（`usb online` / `real_type changed`），保证 120W 快充时能解析出比例与 cur_work_cp
- 采集通道拆分：session/event 走 3 文件低频瘦通道；功率路径信号走最新 1 文件 1MB 专用通道（手机端 grep + tail -n 200 封顶），高频 quickchg/buckchg 不再拖死 session/ICL/EPP
- stale 独立：`logs_stale` 只代表 vote/session 主链路，`power_path_logs_stale` 单独输出；功率路径读取失败时保留上次成功状态，页面显示“数据暂时陈旧 / 路径日志读取异常”
- 无线/有线 SC8581 状态彻底解耦：`power_good` 只重置无线 track，`usb online` / `real_type changed` 只重置有线 track；SC8581 operation mode 仅在对应 quickchg 上下文出现后写入对应 track，避免跨会话/跨模式互相污染
- 有线 Buck 确认：当前有线会话出现 `mca_strategy_buckchg / strategy_buckchg` 活动且无 CP 证据时，路径判为“Buck 直充（有线）”（如 HVDCP_3/QC3）；有线状态按时间顺序 + CP 证据优先（mode>0 / mode=0 后 cur_work_cp → CP，mode=0 或 buckchg → Buck，均无 → 待确认）
- ICL 带日志时间与采集时刻，`power_good_off` 后自动清零，旧会话残留一目了然
- 总仲裁只显示实际结果：无线输入侧 = RX 输出电流上限 + 实测 RX 输出；不再把 `wls_icl` 或 `wireless_buck_input` effective 当成“实际输入限流”展示
- 不再用 `wls_icl` 与 `iout` 做“限流未生效”判定（BPP/EPP+/QC 均不比较）：反编译证据链为 `effective → strategy_wireless_set_input_curr_limit → platform_class_buckchg_ops_set_wls_input_curr_lmt → mca_adsp_glink_write_prop(0x1003)`，落地权在闭源 ADSP 固件
- 热控电流上限（wired/wireless_chg_curr）按 µA → mA 换算并标注“策略上限”，不再把原始值当 mA 显示
- 电池 INPUT_CURRENT_LIMIT 按 µA→mA、TIME_TO_FULL_NOW 按秒→分钟换算
- 主界面精简：删除“芯片与系统 / 电荷泵与电池 / 电流投票与限流 / 电池标准属性”四张卡；对应 sysfs 节点与 battery uevent 仍完整保留在 `/api/data` 与页面底部原始 JSON，排查不受影响
- 页面层次：实时数据 → 曲线 → 当前充电限制 → MCA 投票详情 → 会话档案
- 会话档案只保留最近 3 个
- real_type=Unknown 状态化显示（放电=未连接，充电=未识别）
- 投票未知主题不再默认 mA 单位；effective/投票表行 client 正则与 voting 行统一
- 实时会话档案（插拔 / 私有认证 / 快充协商 / SmartEndura 介入）
- 实时曲线（输入功率、电池电流、vout、iout）
- 虚拟温度与生效场景（来自 mi_thermald thermal.dump）
- 双周期采集：实时数据 3 秒、日志数据 10 秒，顶部有独立倒计时
- 日志读取失败时保留上次成功数据并显示"日志读取失败"提示

## 安装

1. 在 Releases 或 Actions Artifacts 下载 `app-debug.apk`
2. 安装后打开，首次会检测 root 权限（KernelSU / Magisk 的 su）
3. 授予 root 后自动开始 3 秒刷新采集

## 从源码构建

```sh
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 仲裁展示说明

v0.10.2 起，首页“总仲裁结果”无线侧只展示：

- **RX 输出电流上限**：`strategy_class_wireless_op_get_rx_iout_limit` 按充电器类型/模式查表得到的允许上限（如 2800mA），随无线会话保持，`work_mode` 切换不失效
- **实际 RX 输出电流**：wls_debug iout 实时遥测

`wireless_buck_input` 的 MCA 仲裁（`effective vote is now ...`）、`wls_icl`
（经 ADSP GLINK prop 0x1003 下发）与 `xm_wls` 能力票保留在详情卡，不再进入总仲裁。

反汇编证据（K80 Pro miro 固件 `mca_*.ko`）：

- `wireless_buck_input`：type=0（Set_And/MIN），默认 1100mA，回调 `strategy_wireless_set_input_curr_limit`
- `wireless_qc` / `xm_wls`：均为真实 mA 投票；MIN(1300,100)=100 时 100 是 effective winner
- `wls_icl` 最终写入：`platform_class_buckchg_ops_set_wls_input_curr_lmt` → `mca_adsp_glink_write_prop(0x1003, value, 4)`，发给 ADSP 固件（有线 USB ICL 对应 prop 0x2008）
- `rx_iout_limit`：按充电器类型/内部模式查表（1000/1100/1700/2000/2500/2800/3800/4900 mA），是 RX 输出电流上限，不是 `wls_icl`

投票类型大部分来自对 miro 固件 `mca_*.ko` 的反汇编核实，而非按单位猜测；`buck_charge_curr` 标注为项目假设（`MIN_ASSUMED`），无驱动 effective 行时仅作“参考推算”：

| 类型 | 含义 | 已核实的 votable 示例 |
|---|---|---|
| MIN | 启用票中取最小 | wireless_buck_input、wireless_*_in、wireless_sw_*_ich、wls_single/multi_chg_cur、div*、single/multi_chg_cur |
| MIN_ASSUMED | 项目假设为最小（未独立核实） | buck_charge_curr（仅参考推算） |
| FIRST_NONZERO | 首个启用且非零的投票 | quick_chg_disable、wls_quick_chg_disable |
| FIRST_ZERO | 首个启用且为零的投票 | quick_chg_en |
| UNKNOWN | 未核实，不做推算，单位也不猜测 | 其余主题 |

## 数据来源（设备内，无需 ADB）

- `/sys/devices/platform/soc/soc:mca_*` 充电框架 sysfs
- `/sys/class/power_supply/battery/uevent` 电池标准属性
- `/data/vendor/bsplog/charge/charge_logger/mca_log` MCA 日志（投票/会话/EPP）
- `/data/vendor/thermal/thermal.dump` mi_thermald 实时状态（虚拟温度/场景/等级）

## 相关仓库

- [charging-live-dashboard](https://github.com/leowood2000/charging-live-dashboard) — Web 版（ADB 采集）
- [charging-thermal-database](https://github.com/leowood2000/charging-thermal-database) — 热控数据库
