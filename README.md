# 充电实时仪表盘（Android）

K80 Pro（及同类 MIUI/HyperOS root 机型）无线/有线充电实时仪表盘 APK。

直接在手机上通过 root（KernelSU / Magisk）读取 sysfs、MCA 内核日志与 mi_thermald 状态，
**不需要 ADB、不需要电脑**。页面为 WebView 内嵌仪表盘，首页即展示实时 KPI 与**投票总仲裁结果**。

## 功能

- 实时 KPI：无线输入功率、电池充电功率/电流/电压、SOC、电池温度
- 电流符号统一：充电为正、放电为负，电池功率正负同理
- 私有快充协商（quick_charge_type / power_max / EPP 协商状态）
- 无线/有线热控实时数据（无线热控限流、有线热控等级、热控档位投票）
- 电流投票实时表 + **总仲裁结果**（生效客户、最终值、推算值）
- MCA 仲裁展示修正：驱动 `effective vote is now` 优先，`wireless loop: icl` 单独显示为“实际下发 ICL”，不再混为一谈
- 每个 votable 标注已核实的仲裁类型（MIN/MAX/NONZERO/ZERO），未知类型不做盲目推算
- 投票解析支持 `voting on/off`，并按主题分别缓存最近变动与结果，避免日志交错串线
- ICL 带日志时间与采集时刻，`power_good_off` 后自动清零，旧会话残留一目了然
- 首页同屏展示 MCA 仲裁值 / 实际下发 ICL / 实时 iout，差值超 200mA 时提示可能为旧日志或不同控制阶段
- 实时会话档案（插拔 / 私有认证 / 快充协商 / SmartEndura 介入）
- 实时曲线（输入功率、电池电流、vout、iout）
- 虚拟温度与生效场景（来自 mi_thermald thermal.dump）
- 双周期采集：实时数据 3 秒、日志数据 20 秒，顶部有独立倒计时
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

首页“总仲裁结果”里的 **无线输入限流** 表示 MCA 驱动的逻辑仲裁结果（来源为内核日志
`effective vote is now ...`）；**实际下发 ICL** 表示 `wireless loop: icl:` 打印的、
经过协议/硬件/保护逻辑处理后的真实下发值。两者含义不同，页面会同时展示并给出差值。

投票类型来自对 miro 固件 `mca_*.ko` 的反汇编核实，而非按单位猜测：

| 类型 | 含义 | 已核实的 votable 示例 |
|---|---|---|
| MIN | 启用票中取最小 | wireless_buck_input、buck_charge_curr、wireless_*_in、wireless_sw_*_ich、wls_single/multi_chg_cur、div*、single/multi_chg_cur |
| FIRST_NONZERO | 首个启用且非零的投票 | quick_chg_disable、wls_quick_chg_disable |
| FIRST_ZERO | 首个启用且为零的投票 | quick_chg_en |
| UNKNOWN | 未核实，不做推算 | 其余主题 |

## 数据来源（设备内，无需 ADB）

- `/sys/devices/platform/soc/soc:mca_*` 充电框架 sysfs
- `/sys/class/power_supply/battery/uevent` 电池标准属性
- `/data/vendor/bsplog/charge/charge_logger/mca_log` MCA 日志（投票/会话/EPP）
- `/data/vendor/thermal/thermal.dump` mi_thermald 实时状态（虚拟温度/场景/等级）

## 相关仓库

- [charging-live-dashboard](https://github.com/leowood2000/charging-live-dashboard) — Web 版（ADB 采集）
- [charging-thermal-database](https://github.com/leowood2000/charging-thermal-database) — 热控数据库
