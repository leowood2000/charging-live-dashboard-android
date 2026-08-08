package com.leowood.chargingdashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 采集 + 解析，输出与 Web 版 /api/data 同构的 JSON 快照。 */
public final class SnapshotCollector {
    private static final String MCA_LOG_DIR = "/data/vendor/bsplog/charge/charge_logger/mca_log";
    private static final String THERMAL_DUMP = "/data/vendor/thermal/thermal.dump";

    private static final String[][] NODES = {
        {"quick_charge_type", "soc:mca_business_charger/quick_charge_type", "私有快充类型", "私有快充协商", "", "text"},
        {"real_type", "soc:mca_business_charger/real_type", "驱动协议类型", "私有快充协商", "", "text"},
        {"power_max", "soc:mca_business_charger/power_max", "协商最大功率", "私有快充协商", "W", "num"},
        {"is_eu_model", "soc:mca_business_charger/is_eu_model", "是否欧版", "私有快充协商", "", "text"},
        {"wls_debug", "soc:mca_strategy_basic_wireless_class/wls_debug", "无线实时参数 vout/vrect/iout", "无线策略实时", "", "wls"},
        {"wls_fc_flag", "soc:mca_strategy_basic_wireless_class/wls_fc_flag", "快充成功标志", "无线策略实时", "", "num"},
        {"wls_car_adapter", "soc:mca_strategy_basic_wireless_class/wls_car_adapter", "车载适配器标志", "无线策略实时", "", "num"},
        {"audio_phone_sts", "soc:mca_strategy_basic_wireless_class/audio_phone_sts", "音频/通话状态", "无线策略实时", "", "num"},
        {"low_inductance_offset", "soc:mca_strategy_basic_wireless_class/low_inductance_offset", "低感量偏移", "无线策略实时", "", "num"},
        {"wired_chg_curr", "soc:mca_charger_thermal/wired_chg_curr", "有线热控电流上限", "有线策略实时", "mA", "ua_to_ma"},
        {"wired_ctrl_limit", "soc:mca_charger_thermal/wired_ctrl_limit", "有线热控等级", "有线策略实时", "", "num"},
        {"ichg_limit", "soc:mca_charge_interface/ichg_limit", "充电电流投票结果", "电流投票与限流", "", "ichg"},
        {"charge_enable", "soc:mca_charge_interface/charge_enable", "充电使能投票", "电流投票与限流", "", "text"},
        {"wireless_chg_curr", "soc:mca_charger_thermal/wireless_chg_curr", "无线热控电流上限", "电流投票与限流", "mA", "ua_to_ma"},
        {"ibus_total", "soc:mca_platform_cp/ibus_total", "电荷泵总线电流", "电荷泵与电池", "mA", "num"},
        {"ibus_delta", "soc:mca_platform_cp/ibus_delta", "电荷泵总线电流差", "电荷泵与电池", "mA", "num"},
        {"btb_master_status", "soc:mca_bmd/btb_master_status", "BTB 主/从状态（单电芯双接口）", "电荷泵与电池", "", "text"},
        {"wireless_chip_fw", "soc:mca_strategy_wireless_revchg_class/wireless_chip_fw", "无线芯片固件", "芯片与系统", "", "text"},
    };

    private static final String BATTERY_UEVENT = "/sys/class/power_supply/battery/uevent";
    private static final String USB_UEVENT = "/sys/class/power_supply/usb/uevent";
    private static final int HISTORY_MAX = 180;
    private static final int SESSION_MAX = 3;
    private static final int SESSION_EVENT_MAX = 100;

    private final Deque<JSONObject> history = new ArrayDeque<>();
    /** 无锁读取的快照字符串；首次即返回 loading 状态，避免启动黑屏等待采集锁。 */
    private volatile String snapshotJson = null;
    /** 慢速日志结果由 collectLogs 独占写入、collectFast 只读，volatile 保证可见性。 */
    private volatile JSONObject lastVoters = new JSONObject();
    private volatile JSONArray lastSessions = new JSONArray();
    private volatile String lastEpp = null;
    /** 驱动实测无线输入限流（wireless loop icl），比投票最小值推算更可信。 */
    private volatile Integer lastWlsIcl = null;
    private volatile Long lastWlsIclAt = null;
    private volatile String lastWlsIclLogTime = null;
    /** quick wireless 最终电池电流目标 cur_max:[Final]，CP 快充路径下真正约束电流的值。 */
    private volatile Integer lastQuickCurMax = null;
    /** wireless loop 行里的 buck_fcc（电池侧 FCC 上限），cur_max 缺失时的回退。 */
    private volatile Integer lastBuckFcc = null;
    /** sc8581 电荷泵工作模式：>0 表示 CP 路径生效（此时 buck 输入限流不约束实际电流）。 */
    private volatile Integer lastCpMode = null;
    /** quick wireless 电荷泵分压比 work_mode（1/2/4 → 1:1/2:1/4:1）。 */
    private volatile Integer lastCpWorkMode = null;
    /** select_max_ibat 完整决策（输入 + cur_max Final + 日志时间）。 */
    private volatile JSONObject lastCurDecision = null;
    /** 有线功率路径状态：cp / buck / unknown（时间顺序 + CP 证据优先）。 */
    private volatile String lastWiredState = "unknown";
    private volatile Integer lastWiredCpRatio = null;
    private volatile boolean lastWiredCurCp = false;
    /** 有线 Buck 证据：buckchg 策略活动（无 CP 证据时据此判 Buck）。 */
    private volatile boolean lastWiredBuck = false;
    /** 有线输入遥测缓存：CP regulation 与 Buck status 各一份，按 wired_cp.state 选择来源。 */
    private volatile JSONObject lastWiredCpTel = null;
    private volatile JSONObject lastWiredBuckTel = null;
    /** 新会话/协议变化后尚无策略遥测：页面回退 USB uevent 并标记“等待策略遥测”。 */
    private volatile boolean lastWiredTelWaiting = false;
    private volatile long lastLogsUpdatedAt = System.currentTimeMillis();
    private volatile boolean logsStale = false;
    private volatile boolean powerPathLogsStale = false;
    private String lastError = "";
    private boolean rootOk = false;
    private final int utcOffsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;

    public String getSnapshotJson() {
        if (snapshotJson == null) {
            snapshotJson = loadingSnapshot().toString();
        }
        return snapshotJson;
    }

    /** 快速采集：sysfs + battery + thermal.dump + history，每 3 秒一次。 */
    public void collectFast() {
        if (!rootOk) {
            rootOk = RootShell.isRootAvailable();
            if (!rootOk) {
                lastError = "需要 root 权限（KernelSU / Magisk）";
                publishJson(loadingSnapshot(lastError));
                return;
            }
        }
        try {
            String batch = readBatch();
            if (batch.isEmpty()) {
                lastError = "sysfs 读取失败（su 返回空）";
                publishJson(loadingSnapshot(lastError));
                return;
            }
            JSONObject parsed = build(batch);
            parsed.put("mode", "live");
            parsed.put("connected", true);
            parsed.put("thermal", parseThermalDump(
                    RootShell.exec("tail -c 65536 " + THERMAL_DUMP
                            + " | grep -a -E 'MONITOR-WIRELESS' | tail -n 3", 15)));

            JSONObject sample = new JSONObject();
            JSONObject d = parsed.getJSONObject("derived");
            sample.put("t", System.currentTimeMillis() / 1000.0)
                    .put("input_source", d.opt("input_source"))
                    .put("input_voltage_mv", d.opt("input_voltage_mv"))
                    .put("input_current_ma", d.opt("input_current_ma"))
                    .put("vout", d.opt("vout")).put("vrect", d.opt("vrect"))
                    .put("iout", d.opt("iout")).put("input_power_w", d.opt("input_power_w"))
                    .put("battery_power_w", d.opt("battery_power_w"))
                    .put("batt_current_ma", d.opt("batt_current_ma"))
                    .put("batt_voltage_mv", d.opt("batt_voltage_mv"))
                    .put("capacity", d.opt("capacity")).put("temp_c", d.opt("temp_c"));
            synchronized (history) {
                history.addLast(sample);
                while (history.size() > HISTORY_MAX) history.removeFirst();
                parsed.put("history", new JSONArray(new ArrayList<>(history)));
            }
            publishSnapshot(parsed);
        } catch (Exception e) {
            lastError = "采集异常: " + e.getMessage();
            publishJson(errorSnapshot(lastError));
        }
    }

    /** 慢速采集：投票 + 会话 + EPP，每 20 秒一次，避免高频重扫十几 MB 日志。 */
    public void collectLogs() {
        try {
            String voteLog = readVoteLogs();
            Log.i("ChargeDashboard", "voteLogLen=" + voteLog.length());
            String sessionLog = readSessionLogs();
            String ppLog = readPowerPathLogs();
            // 用完成标记区分“命令成功但无匹配”和“命令失败”
            boolean sessionReadOk = sessionLog.contains("__SESS_OK__");
            sessionLog = sessionLog.replace("__SESS_OK__", "").trim();
            boolean ppReadOk = ppLog.contains("__PP_OK__");
            ppLog = ppLog.replace("__PP_OK__", "").trim();
            boolean voteReadOk = !voteLog.isEmpty();
            // 读取成功才解析；解析无匹配（如当前无投票输出）不覆盖旧数据也不算失败
            if (voteReadOk) {
                JSONObject voters = parseVotes(voteLog);
                if (voters.length() > 0) lastVoters = voters;
            }
            if (sessionReadOk) {
                JSONArray sessions = parseSessions(sessionLog);
                if (sessions.length() > 0) lastSessions = sessions;
                String epp = parseEpp(sessionLog);
                if (epp != null) lastEpp = epp;
                if (isLastWirelessPowerOff(sessionLog)) {
                    // 无线已断开：清掉旧 ICL，避免上一个会话的值继续覆盖显示
                    lastWlsIcl = null;
                    lastWlsIclAt = null;
                    lastWlsIclLogTime = null;
                    lastQuickCurMax = null;
                    lastBuckFcc = null;
                    lastCpMode = null;
                    lastCpWorkMode = null;
                    lastCurDecision = null;
                } else {
                    WlsIcl icl = parseWlsIcl(sessionLog);
                    if (icl != null) {
                        lastWlsIcl = icl.value;
                        lastWlsIclAt = icl.at;
                        lastWlsIclLogTime = icl.logTime;
                    }
                    Integer bf = parseBuckFcc(sessionLog);
                    if (bf != null) lastBuckFcc = bf;
                }
            }
            // 功率路径通道：高频信号，手机端已 tail -n 200 封顶
            if (ppReadOk) {
                Integer qcm = parseQuickCurMax(ppLog);
                if (qcm != null) lastQuickCurMax = qcm;
                JSONObject cpState = parseSessionCpState(ppLog);
                // 有线输入遥测：只解析最后一次会话边界之后的日志段；
                // 同一行（log_time/vbus/ibus）不刷新 at，避免旧值被伪装成刚刚采到；
                // 新会话/协议变化后尚无遥测时清空缓存并标记等待，页面回退 USB uevent
                if (isWiredDisconnected(ppLog)) {
                    lastWiredCpTel = null;
                    lastWiredBuckTel = null;
                    lastWiredTelWaiting = false;
                } else {
                    String tail = splitAfterLastWiredBoundary(ppLog);
                    JSONObject cpTel = parseWiredCpTelemetry(tail);
                    JSONObject buckTel = parseWiredBuckTelemetry(tail);
                    if (cpTel == null && buckTel == null) {
                        lastWiredCpTel = null;
                        lastWiredBuckTel = null;
                        lastWiredTelWaiting = hasWiredBoundary(ppLog);
                    } else {
                        lastWiredTelWaiting = false;
                        if (cpTel != null && !sameTelKey(cpTel, lastWiredCpTel)) {
                            lastWiredCpTel = cpTel;
                        }
                        if (buckTel != null && !sameTelKey(buckTel, lastWiredBuckTel)) {
                            lastWiredBuckTel = buckTel;
                        }
                    }
                }
                // 无线 track：power_good 边界
                if (cpState.optBoolean("w_boundary", false)) {
                    lastCpMode = cpState.isNull("w_mode") ? null : cpState.getInt("w_mode");
                    lastCpWorkMode = cpState.isNull("w_work") ? null : cpState.getInt("w_work");
                    lastCurDecision = cpState.isNull("w_decision")
                            ? null : cpState.getJSONObject("w_decision");
                } else {
                    if (!cpState.isNull("w_mode")) lastCpMode = cpState.getInt("w_mode");
                    if (!cpState.isNull("w_work")) {
                        lastCpWorkMode = cpState.getInt("w_work");
                    }
                    if (!cpState.isNull("w_decision")) {
                        lastCurDecision = cpState.getJSONObject("w_decision");
                    }
                }
                // 有线 track：usb online / real_type changed 边界
                if (cpState.optBoolean("d_boundary", false)) {
                    lastWiredState = cpState.optString("d_state", "unknown");
                    lastWiredCpRatio = cpState.isNull("d_ratio") ? null : cpState.getInt("d_ratio");
                    lastWiredCurCp = cpState.optBoolean("d_cur_cp", false);
                    lastWiredBuck = cpState.optBoolean("d_buck", false);
                } else {
                    String ds = cpState.optString("d_state", "unknown");
                    if (!"unknown".equals(ds)) {
                        lastWiredState = ds;
                    }
                    if (!cpState.isNull("d_ratio")) {
                        lastWiredCpRatio = cpState.getInt("d_ratio");
                    }
                    if (cpState.optBoolean("d_cur_cp", false)) {
                        lastWiredCurCp = true;
                    }
                    if (cpState.optBoolean("d_buck", false)) {
                        lastWiredBuck = true;
                    }
                }
            }
            // 三条通道独立 stale：功率路径失败不拖累 session/vote 主链路
            logsStale = !voteReadOk || !sessionReadOk;
            powerPathLogsStale = !ppReadOk;
            lastLogsUpdatedAt = System.currentTimeMillis();
            publishLogs();
        } catch (Exception e) {
            Log.w("ChargeDashboard", "collectLogs failed: " + e.getMessage());
        }
    }

    /** 统一发布入口：快/慢采集都经此写 snapshotJson，避免互相覆盖。 */
    private synchronized void publishSnapshot(JSONObject core) throws JSONException {
        core.put("voters", lastVoters);
        core.put("sessions", lastSessions);
        if (lastEpp != null) appendEppNode(core, lastEpp);
        if (lastWlsIcl != null) {
            JSONObject buck = core.getJSONObject("voters").optJSONObject("wireless_buck_input");
            if (buck != null) buck.put("icl", lastWlsIcl)
                    .put("icl_time", lastWlsIclLogTime == null ? "" : lastWlsIclLogTime)
                    .put("icl_at", lastWlsIclAt == null ? 0L : lastWlsIclAt.longValue());
        }
        JSONObject buck = core.getJSONObject("voters").optJSONObject("wireless_buck_input");
        if (buck != null) {
            Integer actual = lastQuickCurMax != null ? lastQuickCurMax : lastBuckFcc;
            if (actual != null) {
                buck.put("actual_limit", actual)
                        .put("actual_limit_source",
                                lastQuickCurMax != null ? "quick_wireless cur_max" : "wireless loop buck_fcc");
            }
            // 三态：cp（本会话 operation mode>0）/ buck（本会话明确 mode=0）/ unknown（无新日志待确认）
            if (lastCpMode != null) {
                buck.put("cp_state", lastCpMode > 0 ? "cp" : "buck");
            } else {
                buck.put("cp_state", "unknown");
            }
            buck.put("cp_active", lastCpMode != null && lastCpMode > 0);
            if (lastCpMode != null && lastCpMode > 0 && lastCpWorkMode != null) {
                buck.put("cp_ratio", lastCpWorkMode);
            }
            if (lastCurDecision != null) buck.put("cur_max_decision", lastCurDecision);
        }
        // 有线 CP 三态：cp / buck / unknown（本会话无 SC8581 模式日志则待确认）
        JSONObject derived = core.optJSONObject("derived");
        if (derived == null) {
            derived = new JSONObject();
            core.put("derived", derived);
        }
        String wstate = lastWiredState;
        derived.put("wired_cp", new JSONObject()
                .put("state", wstate)
                .put("ratio", "cp".equals(wstate) && lastWiredCpRatio != null
                        ? lastWiredCpRatio : JSONObject.NULL)
                .put("active", "cp".equals(wstate))
                .put("cur_work_cp", lastWiredCurCp));
        // 统一刷新日志 meta，避免倒计时/失败标志延迟到下一轮快速采集
        JSONObject meta = core.optJSONObject("meta");
        if (meta == null) meta = new JSONObject();
        meta.put("interval", 3)
                .put("fast_interval", 3)
                .put("logs_interval", 10)
                .put("logs_updated_at", lastLogsUpdatedAt)
                .put("logs_stale", logsStale)
                .put("power_path_logs_stale", powerPathLogsStale)
                .put("adb", "root-direct");
        core.put("meta", meta);
        publishJson(core);
    }

    /** 唯一写入点：所有状态（loading/offline/live）都经此发布，避免并发覆盖。 */
    private synchronized void publishJson(JSONObject data) {
        snapshotJson = data.toString();
    }

    private synchronized void publishLogs() throws JSONException {
        JSONObject cur = new JSONObject(snapshotJson);
        publishSnapshot(cur);
    }

    private static void appendEppNode(JSONObject parsed, String epp) throws JSONException {
        JSONArray nodes = parsed.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            if ("epp".equals(nodes.getJSONObject(i).optString("id"))) {
                nodes.getJSONObject(i).put("value", epp).put("ok", true);
                return;
            }
        }
        nodes.put(new JSONObject()
                .put("id", "epp").put("label", "EPP 协商状态")
                .put("group", "无线策略实时").put("unit", "")
                .put("fmt", "epp").put("value", epp).put("ok", true));
    }

    private JSONObject errorSnapshot(String msg) {
        return baseSnapshot("offline", msg);
    }

    private JSONObject loadingSnapshot() {
        return baseSnapshot("loading", "正在申请 root 权限并采集数据");
    }

    private JSONObject loadingSnapshot(String msg) {
        return baseSnapshot("loading", msg);
    }

    private JSONObject baseSnapshot(String mode, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis() / 1000.0)
                    .put("iso", isoNow()).put("mode", mode)
                    .put("connected", false).put("error", msg)
                    .put("nodes", new JSONArray()).put("battery", new JSONObject())
                    .put("derived", new JSONObject()).put("history", new JSONArray())
                    .put("voters", new JSONObject()).put("sessions", new JSONArray())
                    .put("thermal", new JSONObject())
                    .put("meta", new JSONObject().put("interval", 3).put("adb", "root-direct"));
        } catch (JSONException ignored) {}
        return o;
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
    }

    /** 一条 su 脚本批量 cat 所有节点 + battery uevent，###N 分隔。 */
    private String readBatch() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NODES.length; i++) {
            sb.append("echo '###").append(i).append("'; cat '")
                    .append("/sys/devices/platform/soc/").append(NODES[i][1])
                    .append("' 2>/dev/null; ");
        }
        sb.append("echo '###").append(NODES.length).append("'; cat '")
                .append(BATTERY_UEVENT).append("' 2>/dev/null; ")
                .append("echo '###").append(NODES.length + 1).append("'; cat '")
                .append(USB_UEVENT).append("' 2>/dev/null");
        return RootShell.exec(sb.toString(), 20);
    }

    private String readVoteLogs() {
        String fname = RootShell.exec("ls -t " + MCA_LOG_DIR + " | head -n 1", 10).trim();
        if (!fname.matches("[A-Za-z0-9_.\\-]+")) return "";
        return RootShell.exec("tail -c 2097152 " + MCA_LOG_DIR + "/" + fname
                + " | grep -a -E 'mca_vote'", 15);
    }

    private String readSessionLogs() {
        String files = RootShell.exec("ls -t " + MCA_LOG_DIR + " | head -n 3", 10).trim();
        if (files.isEmpty()) return "";
        String pattern = "power_good|AUTHEN_FINISH|uuid_value|TX_ADAPTER|FAST_CHARGE|fast chg success|set chg current|open path ibus|smartchg_soc_limit_callback|strategy_wireless_get_qc_enable|strategy_wireless_get_charging_info";
        StringBuilder script = new StringBuilder();
        String[] logFiles = files.split("\n");
        // ls -t 是最新在前；解析时按旧 -> 新拼接，保证会话时间线顺序正确
        for (int i = logFiles.length - 1; i >= 0; i--) {
            String f = logFiles[i].trim();
            if (!f.matches("[A-Za-z0-9_.\\-]+")) continue;
            script.append("tail -c 4194304 ").append(MCA_LOG_DIR).append('/').append(f)
                    .append(" | grep -a -E '").append(pattern)
                    .append("' | grep -v sysfs_show; ");
        }
        script.append("echo __SESS_OK__");
        return RootShell.exec(script.toString(), 25);
    }

    /** 功率路径高频信号专用通道：只读最新文件 1MB，手机端 grep + tail 200 后返回。 */
    private String readPowerPathLogs() {
        String fname = RootShell.exec("ls -t " + MCA_LOG_DIR + " | head -n 1", 10).trim();
        if (!fname.matches("[A-Za-z0-9_.\\-]+")) return "";
        String pattern = "power_good|usb online|real_type changed|"
                + "sc8581_set_operation_mode|"
                + "mca_quick_charge_update_work_mode_para|"
                + "strategy_quickchg_map_ibus_to_fsw|"
                + "cur_work_cp|"
                + "strategy_buckchg_charge_limit|"
                + "strategy_buckchg_update_charge_status|"
                + "mca_quick_charge_regulation|"
                + "mca_wireless_quick_charge_select_cur_work_mode|"
                + "mca_wireless_quick_charge_select_max_ibat";
        return RootShell.exec("tail -c 1048576 " + MCA_LOG_DIR + "/" + fname
                + " | grep -a -E '" + pattern + "' | tail -n 200; echo __PP_OK__", 15);
    }

    private JSONObject build(String batch) throws JSONException {
        // 解析 ###N 块
        String[] raw = batch.split("(?=###\\d+)");
        JSONObject nodesObj = new JSONObject();
        JSONObject batteryRaw = new JSONObject();
        JSONObject usbRaw = new JSONObject();
        for (String part : raw) {
            if (!part.startsWith("###")) continue;
            String num = part.substring(3).split("\\s", 2)[0];
            String body = part.substring(3 + num.length()).trim();
            int i = Integer.parseInt(num);
            if (i < NODES.length) {
                nodesObj.put(NODES[i][0], new JSONObject()
                        .put("raw", body).put("value", body).put("ok", !body.isEmpty()));
            } else if (i == NODES.length) {
                batteryRaw = parseUevent(body);
            } else if (i == NODES.length + 1) {
                usbRaw = parseUevent(body);
            }
        }

        JSONArray nodeList = new JSONArray();
        for (String[] n : NODES) {
            JSONObject item = nodesObj.optJSONObject(n[0]);
            String v = item == null ? "" : item.optString("value");
            boolean ok = item != null && item.optBoolean("ok");
            nodeList.put(new JSONObject()
                    .put("id", n[0]).put("label", n[2]).put("group", n[3])
                    .put("unit", n[4]).put("fmt", n[5])
                    .put("value", v).put("ok", ok));
        }

        JSONObject wlsNode = nodesObj.optJSONObject("wls_debug");
        JSONObject wls = parseWlsDebug(wlsNode == null ? "" : wlsNode.optString("value"));
        // 统一符号：充电为正、放电为负（AOSP 约定，不依赖厂商原始符号）
        double rawBattCurMa = optNum(batteryRaw, "CURRENT_NOW") / 1000.0;
        String battStatus = batteryRaw.optString("STATUS", "");
        double battCurMa = rawBattCurMa;
        if (Double.isFinite(rawBattCurMa)) {
            if ("Charging".equalsIgnoreCase(battStatus)) {
                battCurMa = Math.abs(rawBattCurMa);
            } else if ("Discharging".equalsIgnoreCase(battStatus)) {
                battCurMa = -Math.abs(rawBattCurMa);
            }
        }
        double battVolMv = optNum(batteryRaw, "VOLTAGE_NOW") / 1000.0;
        double tempC = optNum(batteryRaw, "TEMP") / 10.0;
        double capacity = optNum(batteryRaw, "CAPACITY");

        JSONObject derived = new JSONObject();
        double vout = wls.optDouble("vout", Double.NaN);
        double iout = wls.optDouble("iout", Double.NaN);
        putFinite(derived, "vout", vout);
        putFinite(derived, "vrect", wls.optDouble("vrect", Double.NaN));
        putFinite(derived, "iout", iout);

        // 有线输入遥测：按 wired_cp.state 选择来源（CP→regulation，Buck→buckchg，
        // unknown→最新一条），USB uevent 仅作兜底且永远带 source/时间。
        boolean usbOnline = "1".equals(usbRaw.optString("ONLINE", ""));
        boolean usbKnownOff = "0".equals(usbRaw.optString("ONLINE", ""));
        double usbVbusMv = optNum(usbRaw, "VOLTAGE_NOW") / 1000.0;
        double usbIbusMa = optNum(usbRaw, "CURRENT_NOW") / 1000.0;

        JSONObject cpTel = lastWiredCpTel;
        JSONObject buckTel = lastWiredBuckTel;
        JSONObject chosen;
        String wstate = lastWiredState == null ? "unknown" : lastWiredState;
        if ("cp".equals(wstate)) {
            chosen = cpTel != null ? cpTel : buckTel;
        } else if ("buck".equals(wstate)) {
            chosen = buckTel != null ? buckTel : cpTel;
        } else if (cpTel != null && buckTel != null) {
            chosen = cpTel.optString("log_time", "").compareTo(
                    buckTel.optString("log_time", "")) >= 0 ? cpTel : buckTel;
        } else {
            chosen = cpTel != null ? cpTel : buckTel;
        }

        long nowMs = System.currentTimeMillis();
        boolean wiredStale = false;
        if (chosen != null) {
            long age = nowMs - chosen.optLong("at", 0L);
            long limit = "quick_charge_regulation".equals(chosen.optString("source", ""))
                    ? 12000 : 25000;
            wiredStale = age > limit;
        }
        double wiredVbusMv = Double.NaN;
        double wiredIbusMa = Double.NaN;
        String wiredSource = null;
        String wiredLogTime = "";
        long wiredAt = 0L;
        // 遥测已陈旧且 USB uevent 在线时，用每 3s 快采的 uevent 覆盖（仍保留 source）
        if (chosen != null && !usbKnownOff && !(wiredStale && usbOnline)) {
            wiredVbusMv = chosen.optDouble("vbus_mv", Double.NaN);
            wiredIbusMa = chosen.optDouble("ibus_ma", Double.NaN);
            wiredSource = chosen.optString("source", null);
            wiredLogTime = chosen.optString("log_time", "");
            wiredAt = chosen.optLong("at", 0L);
        } else if (usbOnline) {
            wiredVbusMv = usbVbusMv;
            wiredIbusMa = usbIbusMa;
            wiredSource = "usb_uevent";
            wiredAt = nowMs;
        }
        boolean wiredOnline = Double.isFinite(wiredVbusMv) && Double.isFinite(wiredIbusMa);
        // mV × mA = µW，直接换算成 W（除以 1e6），前端只显示 W
        double wiredPower = wiredOnline ? wiredVbusMv * wiredIbusMa / 1e6 : Double.NaN;

        // 当前输入源抽象层：有线优先（避免旧无线残留值覆盖），无输入时为 none
        String inputSource;
        double inputVolMv = Double.NaN;
        double inputCurMa = Double.NaN;
        double inputPower = Double.NaN;
        if (wiredOnline) {
            inputSource = "wired";
            inputVolMv = wiredVbusMv;
            inputCurMa = wiredIbusMa;
            inputPower = wiredPower;
        } else if (Double.isFinite(vout) && Double.isFinite(iout)
                && (vout != 0 || iout != 0)) {
            inputSource = "wireless";
            inputVolMv = vout;
            inputCurMa = iout;
            inputPower = vout * iout / 1e6;
        } else {
            inputSource = "none";
        }

        derived.put("input_source", inputSource);
        putFinite(derived, "input_power_w", inputPower);
        putFinite(derived, "input_voltage_mv", inputVolMv);
        putFinite(derived, "input_current_ma", inputCurMa);
        derived.put("wired_online", wiredOnline);
        putFinite(derived, "wired_vbus_mv", wiredVbusMv);
        putFinite(derived, "wired_ibus_ma", wiredIbusMa);
        putFinite(derived, "wired_input_power_w", wiredPower);
        derived.put("wired_input_source", wiredSource == null ? JSONObject.NULL : wiredSource);
        if (wiredAt == 0L) {
            derived.put("wired_input_at", JSONObject.NULL);
        } else {
            derived.put("wired_input_at", wiredAt);
        }
        derived.put("wired_input_log_time", wiredLogTime);
        long wiredAge = wiredAt == 0L ? -1L : (nowMs - wiredAt) / 1000;
        if (wiredAge < 0) {
            derived.put("wired_input_age", JSONObject.NULL);
        } else {
            derived.put("wired_input_age", wiredAge);
        }
        derived.put("wired_input_waiting",
                lastWiredTelWaiting && chosen == null && usbOnline);
        derived.put("wired_input_stale", wiredStale);
        derived.put("wired_usb_online", usbOnline);
        putFinite(derived, "battery_power_w", battCurMa * battVolMv / 1e6);
        putFinite(derived, "batt_current_ma", battCurMa);
        putFinite(derived, "batt_voltage_mv", battVolMv);
        putFinite(derived, "capacity", capacity);
        putFinite(derived, "temp_c", tempC);
        putFinite(derived, "tx_adapter", wls.optDouble("tx_adapter", Double.NaN));

        JSONObject battery = new JSONObject();
        for (String k : new String[]{"current_now", "voltage_now", "capacity", "temp",
                "status", "health", "cycle_count", "charge_full", "technology",
                "charge_counter", "input_current_limit", "time_to_full_now", "voltage_max_design",
                "model_name", "present", "capacity_level"}) {
            String v = batteryRaw.optString(k.toUpperCase(Locale.ROOT), "");
            battery.put(k, new JSONObject().put("raw", v).put("value", v).put("ok", !v.isEmpty()));
        }

        // real_type 状态化：Unknown 在放电/未充电时是正常的，不当作采集失败
        for (int i = 0; i < nodeList.length(); i++) {
            JSONObject n = nodeList.getJSONObject(i);
            if (!"real_type".equals(n.optString("id"))) continue;
            String v = n.optString("value", "");
            if (v.equalsIgnoreCase("unknown") || v.isEmpty()) {
                if ("Discharging".equalsIgnoreCase(battStatus)
                        || "Not charging".equalsIgnoreCase(battStatus)) {
                    n.put("value", "未连接（放电中）").put("ok", true);
                } else if ("Charging".equalsIgnoreCase(battStatus)) {
                    n.put("value", "未识别（充电中）").put("ok", true);
                }
            }
            break;
        }

        return new JSONObject()
                .put("ts", System.currentTimeMillis() / 1000.0)
                .put("iso", isoNow())
                .put("nodes", nodeList)
                .put("battery", battery)
                .put("derived", derived)
                .put("meta", new JSONObject()
                        .put("interval", 3)
                        .put("fast_interval", 3)
                        .put("logs_interval", 10)
                        .put("logs_updated_at", lastLogsUpdatedAt)
                        .put("logs_stale", logsStale)
                        .put("power_path_logs_stale", powerPathLogsStale)
                        .put("adb", "root-direct"));
    }

    private static double optNum(JSONObject o, String key) {
        try {
            return Double.parseDouble(o.optString(key, "").trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static void putFinite(JSONObject o, String key, double v) throws JSONException {
        o.put(key, Double.isFinite(v) ? v : JSONObject.NULL);
    }

    private static JSONObject parseUevent(String text) throws JSONException {
        JSONObject o = new JSONObject();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            if (k.startsWith("POWER_SUPPLY_")) k = k.substring("POWER_SUPPLY_".length());
            o.put(k, line.substring(eq + 1).trim());
        }
        return o;
    }

    private static JSONObject parseWlsDebug(String text) throws JSONException {
        JSONObject o = new JSONObject();
        Matcher m = Pattern.compile("([A-Za-z_]+)\\s*=\\s*(-?\\d+(?:\\.\\d+)?)").matcher(text);
        while (m.find()) o.put(m.group(1), Double.parseDouble(m.group(2)));
        return o;
    }

    // ---------------- 投票 ----------------
    private static final Pattern VOTE_TIME_RE = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2}:\\d{3})");
    private static final Pattern VOTE_CHANGED_RE = Pattern.compile(
            "mca_vote:\\d+ (\\w+): ([A-Za-z0-9_.@:/\\-]+),(\\d+) voting (on|off) of val=(-?\\d+)");
    private static final Pattern VOTE_RESULT_RE = Pattern.compile("mca_vote:\\d+ (\\w+): effective vote is now (-?\\d+) voted by ([A-Za-z0-9_.@:/\\-]+),(\\d+)");
    private static final Pattern VOTE_HEADER_RE = Pattern.compile("mca_vote:\\d+ (\\w+) VOTER:");
    private static final Pattern VOTE_ROW_RE = Pattern.compile("(\\d+)\\.([A-Za-z0-9_.@:/\\-]+)\\s+(\\d+)\\s+(-?\\d+)");
    private static final java.util.Map<String, String> VOTE_UNITS = new java.util.HashMap<>();
    static {
        VOTE_UNITS.put("term_volt", "mV");
        VOTE_UNITS.put("chg_enable", "");
        VOTE_UNITS.put("quick_chg_disable", "");
        VOTE_UNITS.put("wls_quick_chg_disable", "");
        // 已从 .ko 核实为电流类投票的主题才显式标注 mA，未知主题默认空单位
        VOTE_UNITS.put("wireless_buck_input", "mA");
        VOTE_UNITS.put("buck_charge_curr", "mA");
        VOTE_UNITS.put("buck_input", "mA");
        VOTE_UNITS.put("wireless_bpp_in", "mA");
        VOTE_UNITS.put("wireless_bppqc2_in", "mA");
        VOTE_UNITS.put("wireless_bppqc3_in", "mA");
        VOTE_UNITS.put("wireless_epp_in", "mA");
        VOTE_UNITS.put("wireless_auth_20w", "mA");
        VOTE_UNITS.put("wireless_auth_30w", "mA");
        VOTE_UNITS.put("wireless_auth_50w", "mA");
        VOTE_UNITS.put("wireless_auth_80w", "mA");
        VOTE_UNITS.put("wireless_auth_voice_box", "mA");
        VOTE_UNITS.put("wireless_auth_magnet_30w", "mA");
        VOTE_UNITS.put("wireless_sw_qc_ich", "mA");
        VOTE_UNITS.put("wireless_sw_thermal_ich", "mA");
        VOTE_UNITS.put("wls_single_chg_cur", "mA");
        VOTE_UNITS.put("wls_multi_chg_cur", "mA");
        VOTE_UNITS.put("div1_single", "mA");
        VOTE_UNITS.put("div1_multi", "mA");
        VOTE_UNITS.put("div2_single", "mA");
        VOTE_UNITS.put("div2_multi", "mA");
        VOTE_UNITS.put("div4_single", "mA");
        VOTE_UNITS.put("div4_multi", "mA");
        VOTE_UNITS.put("thermal_flip", "mA");
        VOTE_UNITS.put("single_chg_cur", "mA");
        VOTE_UNITS.put("multi_chg_cur", "mA");
    }
    /** 已从 miro 固件 .ko 反汇编核实的仲裁类型：MIN/MAX/FIRST_NONZERO/FIRST_ZERO/UNKNOWN。 */
    private static final java.util.Map<String, String> VOTE_POLICIES = new java.util.HashMap<>();
    static {
        // mca_basic_wireless.ko：mca_create_votable(..., 0, ...) 全部为 MIN
        VOTE_POLICIES.put("wireless_buck_input", "MIN");
        VOTE_POLICIES.put("wireless_bpp_in", "MIN");
        VOTE_POLICIES.put("wireless_bppqc2_in", "MIN");
        VOTE_POLICIES.put("wireless_bppqc3_in", "MIN");
        VOTE_POLICIES.put("wireless_epp_in", "MIN");
        VOTE_POLICIES.put("wireless_auth_20w", "MIN");
        VOTE_POLICIES.put("wireless_auth_30w", "MIN");
        VOTE_POLICIES.put("wireless_auth_50w", "MIN");
        VOTE_POLICIES.put("wireless_auth_80w", "MIN");
        VOTE_POLICIES.put("wireless_auth_voice_box", "MIN");
        VOTE_POLICIES.put("wireless_auth_magnet_30w", "MIN");
        VOTE_POLICIES.put("wireless_sw_qc_ich", "MIN");
        VOTE_POLICIES.put("wireless_sw_thermal_ich", "MIN");
        // 项目配置（用户确认）：有线 buck 充电电流按 MIN 推算
        VOTE_POLICIES.put("buck_charge_curr", "MIN");
        // mca_quick_wireless.ko：wls_single/multi_chg_cur 为 MIN，disable 为 type2（首个非零）
        VOTE_POLICIES.put("wls_single_chg_cur", "MIN");
        VOTE_POLICIES.put("wls_multi_chg_cur", "MIN");
        VOTE_POLICIES.put("wls_quick_chg_disable", "FIRST_NONZERO");
        // mca_strategy_quickchg（反编译 C）：电流类 type0，disable type2，en type3（首个为零）
        VOTE_POLICIES.put("quick_chg_disable", "FIRST_NONZERO");
        VOTE_POLICIES.put("quick_chg_en", "FIRST_ZERO");
        VOTE_POLICIES.put("div1_single", "MIN");
        VOTE_POLICIES.put("div1_multi", "MIN");
        VOTE_POLICIES.put("div2_single", "MIN");
        VOTE_POLICIES.put("div2_multi", "MIN");
        VOTE_POLICIES.put("div4_single", "MIN");
        VOTE_POLICIES.put("div4_multi", "MIN");
        VOTE_POLICIES.put("thermal_flip", "MIN");
        VOTE_POLICIES.put("single_chg_cur", "MIN");
        VOTE_POLICIES.put("multi_chg_cur", "MIN");
    }

    private JSONObject parseVotes(String text) throws JSONException {
        JSONObject blocks = new JSONObject();
        JSONObject current = null;
        // 按主题分别保存最近一次变动/结果，避免日志交错时互相覆盖
        java.util.Map<String, JSONObject> changesByTopic = new java.util.HashMap<>();
        java.util.Map<String, JSONObject> resultsByTopic = new java.util.HashMap<>();
        for (String line : text.split("\n")) {
            Matcher m = VOTE_CHANGED_RE.matcher(line);
            if (m.find()) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String time = tm.find() ? shiftLogTime(tm.group(1)) : "";
                changesByTopic.put(m.group(1), new JSONObject()
                        .put("topic", m.group(1)).put("client", m.group(2))
                        .put("idx", Integer.parseInt(m.group(3)))
                        .put("enabled", "on".equals(m.group(4)))
                        .put("value", Integer.parseInt(m.group(5)))
                        .put("time", time));
                continue;
            }
            m = VOTE_RESULT_RE.matcher(line);
            if (m.find()) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String time = tm.find() ? shiftLogTime(tm.group(1)) : "";
                resultsByTopic.put(m.group(1), new JSONObject()
                        .put("topic", m.group(1)).put("value", Integer.parseInt(m.group(2)))
                        .put("client", m.group(3)).put("idx", Integer.parseInt(m.group(4)))
                        .put("time", time));
                continue;
            }
            m = VOTE_HEADER_RE.matcher(line);
            if (m.find()) {
                String topic = m.group(1);
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String time = tm.find() ? shiftLogTime(tm.group(1)) : "";
                current = new JSONObject()
                        .put("topic", topic).put("time", time)
                        .put("unit", VOTE_UNITS.getOrDefault(topic, ""))
                        .put("policy", VOTE_POLICIES.getOrDefault(topic, "UNKNOWN"))
                        .put("changed", changesByTopic.get(topic) != null
                                ? changesByTopic.get(topic) : JSONObject.NULL)
                        .put("result", resultsByTopic.get(topic) != null
                                ? resultsByTopic.get(topic) : JSONObject.NULL)
                        .put("rows", new JSONArray());
                blocks.put(topic, current);
                continue;
            }
            if (current != null) {
                Matcher rm = VOTE_ROW_RE.matcher(line);
                if (rm.find()) {
                    current.getJSONArray("rows").put(new JSONObject()
                            .put("idx", Integer.parseInt(rm.group(1))).put("client", rm.group(2))
                            .put("enable", Integer.parseInt(rm.group(3)))
                            .put("value", Integer.parseInt(rm.group(4))));
                }
            }
        }
        return blocks;
    }

    private String shiftLogTime(String t) {
        Matcher m = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}):(\\d{3})").matcher(t);
        if (!m.matches()) return t;
        int total = (Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60
                + Integer.parseInt(m.group(3)) + utcOffsetMinutes * 60) % 86400;
        return String.format(Locale.ROOT, "%02d:%02d:%02d:%s", total / 3600,
                (total % 3600) / 60, total % 60, m.group(4));
    }

    // ---------------- 会话 ----------------
    private static final Pattern LOG_TIME_RE = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2}:\\d{3})");
    private static final Pattern UUID_RE = Pattern.compile("uuid_value is (\\S+)");
    private static final Pattern TX_RE = Pattern.compile("POWER_SUPPLY_TX_ADAPTER=(\\d+)");
    private static final Pattern ICHG_RE = Pattern.compile("set chg current (\\d+)");
    private static final Pattern OPEN_RE = Pattern.compile("open path ibus (\\d+)");

    private JSONArray parseSessions(String text) throws JSONException {
        JSONArray sessions = new JSONArray();
        JSONObject cur = null;
        for (String line : text.split("\n")) {
            String kind = null, detail = "";
            if (line.contains("wireless power_good_off")) kind = "off";
            else if (line.contains("wireless power_good_on")) kind = "on";
            else if (line.contains("RX_INT_AUTHEN_FINISH")) kind = "auth";
            else {
                Matcher u = UUID_RE.matcher(line);
                if (u.find()) { kind = "uuid"; detail = u.group(1); }
                else {
                    Matcher t = TX_RE.matcher(line);
                    if (t.find()) { kind = "tx"; detail = "TX_ADAPTER=" + t.group(1); }
                    else if (line.contains("RX_INT_FAST_CHARGE")) kind = "fc";
                    else {
                        Matcher f = Pattern.compile("fast chg success: (\\d+)").matcher(line);
                        if (f.find()) { kind = "fcflag"; detail = f.group(1); }
                        else {
                            Matcher c = ICHG_RE.matcher(line);
                            if (c.find()) { kind = "ichg"; detail = c.group(1); }
                            else {
                                Matcher o = OPEN_RE.matcher(line);
                                if (o.find()) { kind = "open"; detail = o.group(1); }
                                else if (line.contains("smartchg_soc_limit_callback")
                                        && line.contains("effective_result: 1")) kind = "smart";
                            }
                        }
                    }
                }
            }
            if (kind == null) continue;
            if (kind.equals("on")) {
                cur = new JSONObject().put("start", shiftLogTime(timeOf(line))).put("ended", false)
                        .put("events", new JSONArray())
                        .put("uuid", JSONObject.NULL).put("tx_adapter", JSONObject.NULL)
                        .put("fc_flag", JSONObject.NULL).put("opens", 0)
                        .put("smartendura", false)
                        .put("peak_limit_ma", JSONObject.NULL)
                        .put("final_limit_ma", JSONObject.NULL);
                sessions.put(cur);
            }
            if (cur == null) continue;
            JSONArray ev = cur.getJSONArray("events");
            if (kind.equals("on")) {
                ev.put(event(line, "on", "充电板接入", ""));
            } else if (kind.equals("off")) {
                cur.put("ended", true);
                ev.put(event(line, "off", "充电板移除", ""));
            } else if (kind.equals("auth")) {
                ev.put(event(line, "auth", "私有协议认证完成", ""));
            } else if (kind.equals("uuid")) {
                cur.put("uuid", detail);
                ev.put(event(line, "uuid", "认证 UUID", detail));
            } else if (kind.equals("tx")) {
                if (cur.isNull("tx_adapter")) cur.put("tx_adapter", detail);
                ev.put(event(line, "tx", "发射端识别", detail));
            } else if (kind.equals("fc")) {
                ev.put(event(line, "fc", "快充协商成功", ""));
            } else if (kind.equals("fcflag")) {
                cur.put("fc_flag", detail);
                ev.put(event(line, "fcflag", "快充成功标志", detail));
            } else if (kind.equals("ichg")) {
                ev.put(event(line, "ichg", "设置充电电流", detail));
                int v = Integer.parseInt(detail);
                if (cur.isNull("peak_limit_ma") || v > cur.optInt("peak_limit_ma"))
                    cur.put("peak_limit_ma", v);
                cur.put("final_limit_ma", v);
            } else if (kind.equals("open")) {
                cur.put("opens", cur.optInt("opens") + 1);
                ev.put(event(line, "open", "打开快充路径", detail));
            } else if (kind.equals("smart")) {
                cur.put("smartendura", true);
                ev.put(event(line, "smart", "SmartEndura 介入", ""));
            }
        }
        // 限制会话数量与事件数量，避免长期运行后 DOM/内存膨胀
        while (sessions.length() > SESSION_MAX) sessions.remove(0);
        for (int i = 0; i < sessions.length(); i++) {
            JSONArray evs = sessions.getJSONObject(i).getJSONArray("events");
            while (evs.length() > SESSION_EVENT_MAX) evs.remove(0);
        }
        return sessions;
    }

    private JSONObject event(String line, String kind, String label, String detail)
            throws JSONException {
        return new JSONObject().put("kind", kind)
                .put("time", shiftLogTime(timeOf(line)))
                .put("label", label).put("detail", detail);
    }

    private static String timeOf(String line) {
        Matcher m = LOG_TIME_RE.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    // ---------------- EPP / 热控 ----------------
    private String parseEpp(String text) {
        String last = null;
        Matcher m = Pattern.compile("\\bepp:(\\d)").matcher(text);
        while (m.find()) last = m.group(1);
        return last;
    }

    /** wireless loop icl 快照：值 + 日志本地时间 + 解析时刻（用于判断新旧）。 */
    private static final class WlsIcl {
        final int value;
        final long at;
        final String logTime;

        WlsIcl(int value, long at, String logTime) {
            this.value = value;
            this.at = at;
            this.logTime = logTime;
        }
    }

    private static final Pattern WLS_ICL_RE =
            Pattern.compile("wireless loop: icl:(\\d+)");
    private static final Pattern QUICK_CUR_MAX_RE =
            Pattern.compile("cur_max:\\[Final\\]: (\\d+)");
    private static final Pattern BUCK_FCC_RE =
            Pattern.compile("wireless loop: icl:\\d+, buck_fcc:(\\d+)");

    /** 最后一次无线电源事件是否为断开（power_good_off 晚于 power_good_on）。 */
    private boolean isLastWirelessPowerOff(String text) {
        return text.lastIndexOf("wireless power_good_off")
                > text.lastIndexOf("wireless power_good_on");
    }

    /** 取最新 wireless loop icl（驱动实际下发的无线输入限流），附带时间和采集时刻。 */
    private WlsIcl parseWlsIcl(String text) {
        WlsIcl last = null;
        for (String line : text.split("\n")) {
            Matcher m = WLS_ICL_RE.matcher(line);
            if (!m.find()) continue;
            Matcher tm = VOTE_TIME_RE.matcher(line);
            String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
            last = new WlsIcl(Integer.parseInt(m.group(1)), System.currentTimeMillis(), logTime);
        }
        return last;
    }

    /** 取最新 quick wireless 最终电池电流目标 cur_max:[Final]（CP 快充路径的实际约束值）。 */
    private Integer parseQuickCurMax(String text) {
        Integer last = null;
        Matcher m = QUICK_CUR_MAX_RE.matcher(text);
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    /** 取最新 wireless loop 行里的 buck_fcc（电池侧 FCC 上限），cur_max 缺失时回退用。 */
    private Integer parseBuckFcc(String text) {
        Integer last = null;
        Matcher m = BUCK_FCC_RE.matcher(text);
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static final Pattern CP_MODE_RE =
            Pattern.compile("set operation mode (\\d+)");

    /** 取最新 sc8581 电荷泵工作模式（0=关，>0=CP 路径生效）。 */
    private Integer parseCpMode(String text) {
        Integer last = null;
        Matcher m = CP_MODE_RE.matcher(text);
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static final Pattern CP_WORK_MODE_RE =
            Pattern.compile("select_cur_work_mode:.*work_mode=(\\d+)");

    /** 取最新 quick wireless 电荷泵分压比 work_mode（1/2/4 → 1:1/2:1/4:1）。 */
    private Integer parseCpWorkMode(String text) {
        Integer last = null;
        Matcher m = CP_WORK_MODE_RE.matcher(text);
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static final Pattern CUR_DECISION_IN_RE = Pattern.compile(
            "select_max_ibat:445 \\[channel_cur:(\\d+)\\], \\[temp_max_cur:(\\d+)\\], " +
            "\\[tx_adapter_max:(\\d+)\\], \\[sw_qc_ichg:(\\d+)\\],\\[sw_thermal_ichg:(\\d+)\\]");
    private static final Pattern CUR_DECISION_FINAL_RE = Pattern.compile(
            "select_max_ibat:446 cur_max:\\[Final\\]: (\\d+)");
    private static final Pattern WIRED_WORK_MODE_RE = Pattern.compile(
            "update_work_mode_para:.*work_mode: (\\d+)");
    private static final Pattern WIRED_RATIO_RE = Pattern.compile(
            "map_ibus_to_fsw:.*ratio: (\\d+)");
    private static final Pattern WIRELESS_WORK_MODE_RE = Pattern.compile(
            "mca_wireless_quick_charge_select_cur_work_mode:.*work_mode=(\\d+)");

    /** 取最新 select_max_ibat 完整决策：输入项 + cur_max:[Final] + 日志时间。 */
    private JSONObject parseQuickCurDecision(String text) throws JSONException {
        JSONObject inputs = null;
        JSONObject result = null;
        for (String line : text.split("\n")) {
            Matcher m = CUR_DECISION_IN_RE.matcher(line);
            if (m.find()) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                inputs = new JSONObject()
                        .put("channel_cur", Integer.parseInt(m.group(1)))
                        .put("temp_max_cur", Integer.parseInt(m.group(2)))
                        .put("tx_adapter_max", Integer.parseInt(m.group(3)))
                        .put("sw_qc_ichg", Integer.parseInt(m.group(4)))
                        .put("sw_thermal_ichg", Integer.parseInt(m.group(5)))
                        .put("log_time", tm.find() ? shiftLogTime(tm.group(1)) : "")
                        .put("at", System.currentTimeMillis());
                continue;
            }
            Matcher m2 = CUR_DECISION_FINAL_RE.matcher(line);
            if (m2.find() && inputs != null) {
                result = new JSONObject(inputs.toString());
                result.put("final", Integer.parseInt(m2.group(1)));
            }
        }
        return result;
    }

    private static final Pattern WIRED_BUCK_TELEMETRY_RE = Pattern.compile(
            "strategy_buckchg_update_charge_status:1463 pmic_chg_status: (\\d+), " +
            "chg_status: (\\d+), chg_en: \\[(\\d+)\\]\\[(\\w+)\\], chg_type: (\\d+), " +
            "vbat: (\\d+), vbus: (\\d+), ibus: (\\d+)");

    private static final Pattern WIRED_CP_TELEMETRY_RE = Pattern.compile(
            "mca_quick_charge_regulation:1942 cur_stage\\[(\\d+)\\]: " +
            "adp_volt: (\\d+)/(\\d+), " +
            "ibat: ([\\d\\-]+)/([\\d\\-]+)/([\\d\\-]+)/([\\d\\-]+), " +
            "vbat: (\\d+)/(\\d+), ibus: (\\d+),");

    /** 最新一条 buckchg 状态行：vbus/ibus 为 µV/µA，返回 mV/mA。 */
    private JSONObject parseWiredBuckTelemetry(String text) throws JSONException {
        JSONObject last = null;
        for (String line : text.split("\n")) {
            Matcher m = WIRED_BUCK_TELEMETRY_RE.matcher(line);
            if (!m.find()) continue;
            Matcher tm = VOTE_TIME_RE.matcher(line);
            last = new JSONObject()
                    .put("vbus_mv", Integer.parseInt(m.group(7)) / 1000.0)
                    .put("ibus_ma", Integer.parseInt(m.group(8)) / 1000.0)
                    .put("chg_en", Integer.parseInt(m.group(3)))
                    .put("chg_en_client", m.group(4))
                    .put("chg_type", Integer.parseInt(m.group(5)))
                    .put("source", "buckchg_telemetry")
                    .put("log_time", tm.find() ? shiftLogTime(tm.group(1)) : "")
                    .put("at", System.currentTimeMillis());
        }
        return last;
    }

    /** 最新一条有线 quick charge regulation 行：adp_volt 第二值为实测（mV），ibus 为 mA。 */
    private JSONObject parseWiredCpTelemetry(String text) throws JSONException {
        JSONObject last = null;
        for (String line : text.split("\n")) {
            Matcher m = WIRED_CP_TELEMETRY_RE.matcher(line);
            if (!m.find()) continue;
            Matcher tm = VOTE_TIME_RE.matcher(line);
            last = new JSONObject()
                    .put("vbus_mv", Integer.parseInt(m.group(3)))
                    .put("ibus_ma", Integer.parseInt(m.group(10)))
                    .put("chg_en", 1)
                    .put("chg_en_client", "quick_charge")
                    .put("source", "quick_charge_regulation")
                    .put("log_time", tm.find() ? shiftLogTime(tm.group(1)) : "")
                    .put("at", System.currentTimeMillis());
        }
        return last;
    }

    /** 最新一条有线会话边界是否为断开（usb online: 0 / real_type => 0）。 */
    private boolean isWiredDisconnected(String text) {
        String last = "";
        for (String line : text.split("\n")) {
            if (line.contains("usb online:") || line.contains("real_type changed:")) {
                last = line;
            }
        }
        if (last.isEmpty()) return false;
        if (last.contains("usb online: 0")) return true;
        return last.matches(".*real_type changed: \\d+ => 0.*");
    }

    /** 只保留最后一次 usb online / real_type changed 边界之后的日志段。 */
    private String splitAfterLastWiredBoundary(String text) {
        String[] lines = text.split("\n");
        int last = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("usb online:") || lines[i].contains("real_type changed:")) {
                last = i;
            }
        }
        if (last < 0) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = last + 1; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private boolean hasWiredBoundary(String text) {
        for (String line : text.split("\n")) {
            if (line.contains("usb online:") || line.contains("real_type changed:")) {
                return true;
            }
        }
        return false;
    }

    /** telemetry 唯一 key：(log_time, vbus_mv, ibus_ma)；相同则不刷新 at。 */
    private boolean sameTelKey(JSONObject a, JSONObject b) {
        if (b == null) return false;
        return a.optString("log_time", "").equals(b.optString("log_time", ""))
                && Double.compare(a.optDouble("vbus_mv", Double.NaN),
                                  b.optDouble("vbus_mv", Double.NaN)) == 0
                && Double.compare(a.optDouble("ibus_ma", Double.NaN),
                                  b.optDouble("ibus_ma", Double.NaN)) == 0;
    }

    /** 无线/有线 CP 状态彻底解耦：power_good 只重置无线；usb online/real_type changed 只重置有线；
     *  SC8581 operation mode 只在对应 quickchg 上下文出现后写入对应 track。 */
    private JSONObject parseSessionCpState(String text) throws JSONException {
        Integer wMode = null;
        Integer wWork = null;
        JSONObject wDecision = null;
        JSONObject wInputs = null;
        boolean wBoundary = false;
        boolean wCtx = false;
        Integer dMode = null;
        int dModeSeq = -1;
        Integer dRatio = null;
        boolean dCurCp = false;
        int dCurCpSeq = -1;
        boolean dBuck = false;
        boolean dBoundary = false;
        boolean dCtx = false;
        int seq = 0;
        for (String line : text.split("\n")) {
            seq++;
            if (line.contains("power_good_on") || line.contains("power_good_off")) {
                wBoundary = true;
                wMode = null;
                wWork = null;
                wDecision = null;
                wInputs = null;
                wCtx = false;
                continue;
            }
            if (line.contains("usb online: 0") || line.contains("usb online: 1")
                    || line.contains("real_type changed:")) {
                dBoundary = true;
                dMode = null;
                dModeSeq = -1;
                dRatio = null;
                dCurCp = false;
                dCurCpSeq = -1;
                dBuck = false;
                dCtx = false;
                continue;
            }
            if (line.contains("mca_wireless_quick_charge_")) {
                wCtx = true;
                dCtx = false;
            }
            if (line.contains("mca_quick_charge_") || line.contains("strategy_quickchg_")) {
                dCtx = true;
                wCtx = false;
            }
            // 有线 Buck 证据：buckchg 策略活动（无 CP 证据时据此判 Buck）
            if (line.contains("mca_strategy_buckchg") || line.contains("strategy_buckchg")) {
                dBuck = true;
            }
            Matcher m = CP_MODE_RE.matcher(line);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (wCtx) wMode = n;
                if (dCtx) {
                    dMode = n;
                    dModeSeq = seq;
                }
                continue;
            }
            m = WIRELESS_WORK_MODE_RE.matcher(line);
            if (m.find()) {
                wWork = Integer.parseInt(m.group(1));
                continue;
            }
            m = WIRED_WORK_MODE_RE.matcher(line);
            if (m.find()) {
                dRatio = Integer.parseInt(m.group(1));
                continue;
            }
            m = WIRED_RATIO_RE.matcher(line);
            if (m.find()) {
                dRatio = Integer.parseInt(m.group(1));
                continue;
            }
            if (line.contains("mca_quick_charge_select_max_ibat:")
                    && line.contains("cur_work_cp")) {
                dCurCp = true;
                dCurCpSeq = seq;
                continue;
            }
            if (!line.contains("mca_wireless_quick_charge_select_max_ibat:")) {
                continue;
            }
            m = CUR_DECISION_IN_RE.matcher(line);
            if (m.find()) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                wInputs = new JSONObject()
                        .put("channel_cur", Integer.parseInt(m.group(1)))
                        .put("temp_max_cur", Integer.parseInt(m.group(2)))
                        .put("tx_adapter_max", Integer.parseInt(m.group(3)))
                        .put("sw_qc_ichg", Integer.parseInt(m.group(4)))
                        .put("sw_thermal_ichg", Integer.parseInt(m.group(5)))
                        .put("log_time", tm.find() ? shiftLogTime(tm.group(1)) : "")
                        .put("at", System.currentTimeMillis());
                continue;
            }
            Matcher m2 = CUR_DECISION_FINAL_RE.matcher(line);
            if (m2.find() && wInputs != null) {
                wDecision = new JSONObject(wInputs.toString());
                wDecision.put("final", Integer.parseInt(m2.group(1)));
            }
        }
        // 有线最终状态：时间顺序 + CP 证据优先
        String dState;
        if (dMode != null) {
            if (dMode > 0) {
                dState = "cp";
            } else if (dCurCp && dCurCpSeq > dModeSeq) {
                dState = "cp";   // mode=0 之后又出现 cur_work_cp → CP 重新激活
            } else {
                dState = "buck";
            }
        } else if (dCurCp) {
            dState = "cp";
        } else if (dBuck) {
            dState = "buck";
        } else {
            dState = "unknown";
        }
        return new JSONObject()
                .put("w_mode", wMode == null ? JSONObject.NULL : wMode)
                .put("w_work", wWork == null ? JSONObject.NULL : wWork)
                .put("w_decision", wDecision == null ? JSONObject.NULL : wDecision)
                .put("w_boundary", wBoundary)
                .put("d_state", dState)
                .put("d_ratio", dRatio == null ? JSONObject.NULL : dRatio)
                .put("d_cur_cp", dCurCp)
                .put("d_buck", dBuck)
                .put("d_boundary", dBoundary);
    }

    private static final java.util.Map<String, String> THERMAL_SCENES = new java.util.HashMap<>();
    static {
        THERMAL_SCENES.put("MONITOR-WIRELESS", "normal（日常）");
        THERMAL_SCENES.put("CHARGE-MONITOR-WIRELESS", "charge（充电中）");
        THERMAL_SCENES.put("CHG-ONLY-MONITOR-WIRELESS", "chg-only（熄屏充电）");
        THERMAL_SCENES.put("4K-MONITOR-WIRELESS", "4k（4K 录像）");
        THERMAL_SCENES.put("ARVR-MONITOR-WIRELESS", "arvr（AR/VR）");
        THERMAL_SCENES.put("CAMERA-MONITOR-WIRELESS", "camera（相机）");
        THERMAL_SCENES.put("CLASS0-MONITOR-WIRELESS", "class0");
        THERMAL_SCENES.put("DANMU-MONITOR-WIRELESS", "danmu（弹幕）");
        THERMAL_SCENES.put("HIGHFPS-MONITOR-WIRELESS", "highfps（高帧率）");
        THERMAL_SCENES.put("HP-GAME-MONITOR-WIRELESS", "hp-mgame（高性能游戏）");
        THERMAL_SCENES.put("HP-NORMAL-MONITOR-WIRELESS", "hp-normal（高性能常规）");
        THERMAL_SCENES.put("HUANJI-MONITOR-WIRELESS", "huanji（幻迹）");
        THERMAL_SCENES.put("MGAME-MONITOR-WIRELESS", "mgame（中度游戏）");
        THERMAL_SCENES.put("NAVIGATION-MONITOR-WIRELESS", "navigation（导航）");
        THERMAL_SCENES.put("NOLIMITS-MONITOR-WIRELESS", "nolimits（无限制）");
        THERMAL_SCENES.put("PHONE-MONITOR-WIRELESS", "phone（通话）");
        THERMAL_SCENES.put("TGAME-MONITOR-WIRELESS", "tgame（重度游戏）");
        THERMAL_SCENES.put("VIDEO-MONITOR-WIRELESS", "video（视频）");
        THERMAL_SCENES.put("VIDEOCHAT-MONITOR-WIRELESS", "videochat（视频通话）");
        THERMAL_SCENES.put("XINGTIE-MONITOR-WIRELESS", "xingtie（星穹铁道）");
        THERMAL_SCENES.put("YUANSHEN-MONITOR-WIRELESS", "yuanshen（原神）");
        THERMAL_SCENES.put("PER-CLASS0-MONITOR-WIRELESS", "per-class0（性能 Class0）");
        THERMAL_SCENES.put("PER-NORMAL-MONITOR-WIRELESS", "per-normal（性能常规）");
        THERMAL_SCENES.put("PER-VIDEO-MONITOR-WIRELESS", "per-video（性能视频）");
        THERMAL_SCENES.put("CCLASSVIDEO-MONITOR-WIRELESS", "cclassvideo（连续视频）");
        THERMAL_SCENES.put("CGAME-MONITOR-WIRELESS", "cgame（连续游戏）");
    }
    private static final Pattern THERMAL_WLS_RE = Pattern.compile(
            "\\[([A-Z0-9\\-]*MONITOR-WIRELESS)\\]\\[VIRTUAL-SENSOR-FORMULA (\\d+)\\]");
    private static final Pattern THERMAL_TARGET_RE = Pattern.compile("\\[wireless_charge (\\d+)\\]");

    private JSONObject parseThermalDump(String text) throws JSONException {
        JSONObject r = new JSONObject()
                .put("scene", JSONObject.NULL).put("virtual_temp", JSONObject.NULL)
                .put("target", JSONObject.NULL);
        for (String line : text.split("\n")) {
            Matcher m = THERMAL_WLS_RE.matcher(line);
            if (!m.find()) continue;
            r.put("scene", THERMAL_SCENES.getOrDefault(m.group(1), m.group(1)));
            r.put("virtual_temp", Integer.parseInt(m.group(2)) / 1000.0);
            Matcher t = THERMAL_TARGET_RE.matcher(line);
            if (t.find()) r.put("target", Integer.parseInt(t.group(1)));
        }
        return r;
    }
}
