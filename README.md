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

## 数据来源（设备内，无需 ADB）

- `/sys/devices/platform/soc/soc:mca_*` 充电框架 sysfs
- `/sys/class/power_supply/battery/uevent` 电池标准属性
- `/data/vendor/bsplog/charge/charge_logger/mca_log` MCA 日志（投票/会话/EPP）
- `/data/vendor/thermal/thermal.dump` mi_thermald 实时状态（虚拟温度/场景/等级）

## 相关仓库

- [charging-live-dashboard](https://github.com/leowood2000/charging-live-dashboard) — Web 版（ADB 采集）
- [charging-thermal-database](https://github.com/leowood2000/charging-thermal-database) — 热控数据库
