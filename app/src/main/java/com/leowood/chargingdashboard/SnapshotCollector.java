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
    // 实机快充切换测试：Buck 时为 0mA，CP 预启动会短暂出现 3~5mA，
    // 真正承载后直接跃升至 400mA 以上。CP 稳态也可能瞬时读到 0，
    // 因此低电流必须与当前无线会话的 operation mode/work_mode 融合判定。
    private static final double CP_IBUS_BUCK_MAX_MA = 20.0;
    private static final double CP_IBUS_ACTIVE_MIN_MA = 100.0;
    private static final int SESSION_MAX = 1;
    private static final int SESSION_EVENT_MAX = 40;
    // 投票日志是事件式输出。前台运行时只补读新增内容；长时间未运行或
    // 日志轮转后放弃追赶全部历史，最多回退到最新 2MiB。
    private static final long VOTE_INCREMENT_MAX_BYTES = 256L * 1024L;
    private static final long VOTE_OVERLAP_BYTES = 64L * 1024L;
    private static final long VOTE_FALLBACK_BYTES = 2L * 1024L * 1024L;

    private final Deque<JSONObject> history = new ArrayDeque<>();
    /** 无锁读取的快照字符串；首次即返回 loading 状态，避免启动黑屏等待采集锁。 */
    private volatile String snapshotJson = null;
    /** 慢速日志结果由 collectLogs 独占写入、collectFast 只读，volatile 保证可见性。 */
    private volatile JSONObject lastVoters = new JSONObject();
    /** 最近一次 chg_enable effective vote；用于停充后校正有线输入测量源。 */
    private volatile Integer lastChgEnabled = null;
    private volatile JSONArray lastSessions = new JSONArray();
    /** 最近一次有效热控快照；空闲时 thermal.dump 尾部无无线行仍可立即显示。 */
    private volatile JSONObject lastThermal = new JSONObject();
    private volatile String lastEpp = null;
    /** 驱动实测无线输入限流（wireless loop icl），比投票最小值推算更可信。 */
    private volatile Integer lastWlsIcl = null;
    private volatile Long lastWlsIclAt = null;
    private volatile String lastWlsIclLogTime = null;
    private volatile Long lastWlsIclMs = null;
    /** 最近一次无线 work_mode 变化行的归一化日志毫秒（跨文件单调，用于 ICL/effective 时效判定）。 */
    private volatile Long lastWlsWorkModeMs = null;
    /** 当前读取的 mca 日志文件名（mca_log_MMDD_HHMM.log），用于日志时间归一化。 */
    private volatile String lastLogFname = "";
    /** mca_vote 增量读取游标；进程重启/长间隔超限时自动回退到最新 2MiB。 */
    private volatile String lastVoteFile = "";
    private volatile long lastVoteOffset = 0L;
    private volatile boolean voteCursorReady = false;
    private volatile Integer lastWlsChgEn = null;
    /** quick wireless 最终电池电流目标 cur_max:[Final]，CP 快充路径下真正约束电流的值。 */
    private volatile Integer lastQuickCurMax = null;
    /** wireless loop 行里的 buck_fcc（电池侧 FCC 上限），cur_max 缺失时的回退。 */
    private volatile Integer lastBuckFcc = null;
    /** sc8581 电荷泵工作模式：>0 表示 CP 路径生效（此时 buck 输入限流不约束实际电流）。 */
    private volatile Integer lastCpMode = null;
    /** quick wireless 电荷泵分压比 work_mode（1/2/4 → 1:1/2:1/4:1）。 */
    private volatile Integer lastCpWorkMode = null;
    /** 低电流时允许保留 CP 的唯一依据：必须来自当前 power_good 会话。 */
    private volatile boolean lastWlsCpEvidence = false;
    /** select_max_ibat 完整决策（输入 + cur_max Final + 日志时间）。 */
    private volatile JSONObject lastCurDecision = null;
    /** 有线功率路径状态：cp / buck / unknown（时间顺序 + CP 证据优先）。 */
    private volatile String lastWiredState = "unknown";
    private volatile Integer lastWiredCpRatio = null;
    private volatile boolean lastWiredCurCp = false;
    private volatile JSONObject lastWiredCurMax = null;
    private volatile JSONObject lastWiredStageCurMax = null;
    /** HVDCP/QC3 有线分支的实时 FCC 目标（target_limit_fcc_ma），不冒充 Quick Charge Final。 */
    private volatile JSONObject lastWiredQcTarget = null;
    /** 最近一次 USB/real_type 会话边界；用于判断共享 Buck FCC 票是否属于本次有线会话。 */
    private volatile Long lastWiredSessionMs = null;
    /** 日志行 stable key：同一行重复扫描不刷新 at（log_time + 关键值）。 */
    private volatile String lastCurDecisionKey = null;
    private volatile String lastWiredCurMaxKey = null;
    private volatile String lastWiredStageCurMaxKey = null;
    private volatile String lastWiredQcTargetKey = null;
    private volatile String lastWlsIclKey = null;
    /** 有线 Buck 证据：buckchg 策略活动（无 CP 证据时据此判 Buck）。 */
    private volatile boolean lastWiredBuck = false;
    /** 有线输入遥测缓存：CP regulation 与 Buck status 各一份，按 wired_cp.state 选择来源。 */
    private volatile JSONObject lastWiredCpTel = null;
    private volatile JSONObject lastWiredBuckTel = null;
    /** 新会话/协议变化后尚无策略遥测：页面回退 USB uevent 并标记“等待策略遥测”。 */
    private volatile boolean lastWiredTelWaiting = false;
    /** 无线控制模式（bpp drawload / epp_plus/QC）与 RX 输出电流上限。 */
    private volatile String lastWlsMode = "unknown";
    private volatile Integer lastRxIoutLimit = null;
    /** rx_iout_limit 会话状态机：随 power_good 边界，不随 work_mode；窗口滚动不失效。 */
    private volatile boolean rxIoutLimitCaptured = false;
    private volatile Long lastRxIoutLimitAt = null;
    private volatile String lastRxIoutLimitLogTime = null;
    /** SmartEndura / smartchg soc_limit 上下文（用于“当前上游限制”标记）。 */
    private volatile boolean lastSmartenduraSocLimit = false;
    /** 最后一条 power_good_on 的归一化毫秒（会话边界 key，跨文件单调）。 */
    private volatile Long lastWlsSessionMs = null;
    /** 无线充电板物理连接状态：由 power_good_on/off 锁存，不随 iout 低于阈值抖动。 */
    private volatile Boolean lastWirelessConnected = null;
    private volatile long lastLogsUpdatedAt = System.currentTimeMillis();
    private volatile boolean logsStale = false;
    private volatile boolean powerPathLogsStale = false;
    /** 连接/充电时保持日志实时；完全断开时降频，避免空转重扫历史日志。 */
    private volatile boolean logsActive = true;
    private volatile long lastLogsStartedAt = 0L;
    private static final long ACTIVE_LOG_INTERVAL_MS = 10_000L;
    private static final long IDLE_LOG_INTERVAL_MS = 60_000L;
    private static final java.util.Set<String> WIRELESS_VOTER_TOPICS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "wireless_buck_input", "wireless_bpp_in", "wireless_bppqc2_in",
                    "wireless_bppqc3_in", "wireless_epp_in", "wireless_auth_20w",
                    "wireless_auth_30w", "wireless_auth_50w", "wireless_auth_80w",
                    "wireless_auth_voice_box", "wireless_auth_magnet_30w",
                    "wireless_sw_qc_ich", "wireless_sw_thermal_ich",
                    "wls_single_chg_cur", "wls_multi_chg_cur", "wls_quick_chg_disable"));
    private static final java.util.Set<String> WIRED_VOTER_TOPICS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    // buck_charge_curr 是有线/无线共用的 FCC votable，不能在任一单侧
                    // 断开时整体清掉；前端按会话时间确认它是否属于当前路径。
                    "buck_input", "div1_single", "div1_multi",
                    "div2_single", "div2_multi", "div4_single", "div4_multi",
                    "single_chg_cur", "multi_chg_cur", "quick_chg_disable", "quick_chg_en"));
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
    public synchronized void collectFast() {
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
            updateLogsActive(parsed);
            parsed.put("mode", "live");
            parsed.put("connected", true);

            JSONObject sample = new JSONObject();
            JSONObject d = parsed.getJSONObject("derived");
            sample.put("t", System.currentTimeMillis() / 1000.0)
                    .put("input_source", d.opt("input_source"))
                    .put("input_detail_source", d.opt("input_detail_source"))
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

    /** 调度器调用前的轻量门控：连接时 10 秒，完全断开时 60 秒。 */
    public boolean shouldCollectLogs() {
        long interval = logsActive ? ACTIVE_LOG_INTERVAL_MS : IDLE_LOG_INTERVAL_MS;
        return System.currentTimeMillis() - lastLogsStartedAt >= interval;
    }

    private int logsIntervalSeconds() {
        return (int) ((logsActive ? ACTIVE_LOG_INTERVAL_MS : IDLE_LOG_INTERVAL_MS) / 1000L);
    }

    /** 慢速采集：投票 + 会话 + EPP。 */
    public synchronized void collectLogs() {
        lastLogsStartedAt = System.currentTimeMillis();
        try {
            LogBundle logs = readLogs();
            String voteLog = logs.vote;
            Log.i("ChargeDashboard", "voteLogLen=" + voteLog.length());
            String sessionLog = logs.session;
            String ppLog = logs.powerPath;
            boolean voteReadOk = logs.voteOk;
            boolean sessionReadOk = logs.sessionOk;
            boolean ppReadOk = logs.powerPathOk;
            // 读取成功才解析；解析无匹配（如当前无投票输出）不覆盖旧数据也不算失败
            if (voteReadOk) {
                JSONObject voters = parseVotes(voteLog);
                if (voters.length() > 0) {
                    lastVoters = mergeVoteTopics(lastVoters, voters);
                }
            }
            if (sessionReadOk) {
                JSONArray sessions = parseSessions(sessionLog);
                if (sessions.length() > 0) lastSessions = sessions;
                String epp = parseEpp(sessionLog);
                if (epp != null) lastEpp = epp;
                // SmartEndura / smartchg soc_limit 上下文：会话日志中出现即置位
                lastSmartenduraSocLimit = false;
                for (String line : sessionLog.split("\n")) {
                    if (line.contains("smartchg_soc_limit_callback")
                            || line.contains("smart_charge_soc_limit")
                            || line.contains("soc_limit_workfunc")) {
                        lastSmartenduraSocLimit = true;
                        break;
                    }
                }
                if (isLastWirelessPowerOff(sessionLog)) {
                    lastWirelessConnected = false;
                    // 无线已断开：清掉全部无线会话状态，避免上一会话的值继续覆盖显示
                    clearWirelessSessionState();
                    lastWlsSessionMs = null;
                } else {
                    if (sessionLog.contains("wireless power_good_on")) {
                        lastWirelessConnected = true;
                    }
                    // 所有无线执行层数据统一按最近一次 power_good_on 截断，
                    // 避免上一会话的 ICL/buck_fcc 混进新会话
                    String wtail = splitAfterLastWirelessAttach(sessionLog);
                    // 无线会话状态机：只有真正的新 power_good_on（session key 变化）
                    // 才统一清空全部 wireless-session scoped 状态；
                    // 同一 power_good_on 重复出现在日志窗口不重置，避免 Final/ICL 假刷新。
                    Long pgMs = lastWirelessAttachMs(sessionLog);
                    if (pgMs != null && !pgMs.equals(lastWlsSessionMs)) {
                        lastWlsSessionMs = pgMs;
                        clearWirelessSessionState();
                    }
                    JSONObject wm = parseWirelessMode(wtail);
                    lastWlsMode = wm.optString("mode", "unknown");
                    if (!wm.isNull("rx_iout_limit")) {
                        lastRxIoutLimit = wm.getInt("rx_iout_limit");
                        rxIoutLimitCaptured = true;
                        lastRxIoutLimitLogTime = wm.optString("rx_iout_limit_time", "");
                        lastRxIoutLimitAt = logEventAt(lastRxIoutLimitLogTime);
                    }
                    WlsIcl icl = parseWlsIcl(wtail);
                    if (icl != null) {
                        String key = icl.value + "|" + icl.chgEn + "|" + icl.logTime;
                        if (!key.equals(lastWlsIclKey)) {
                            lastWlsIclKey = key;
                            lastWlsIcl = icl.value;
                            lastWlsIclAt = icl.at;
                            lastWlsIclLogTime = icl.logTime;
                            lastWlsIclMs = icl.ms;
                            lastWlsChgEn = icl.chgEn;
                        }
                    }
                    Integer bf = parseBuckFcc(wtail);
                    if (bf != null) lastBuckFcc = bf;
                }
            }
            // 功率路径通道：高频信号，手机端已 tail -n 200 封顶
            if (ppReadOk) {
                boolean ppOff = isLastWirelessPowerOff(ppLog);
                Long pgMs = lastWirelessAttachMs(ppLog);
                // 只有 pp 窗口出现比已确认会话更新的 power_good_on 才算真正新会话；
                // 同一 power_good_on 重复出现在 tail 窗口不重置（避免 Final 假刷新）。
                boolean ppNewSession = !ppOff && pgMs != null
                        && (lastWlsSessionMs == null || pgMs > lastWlsSessionMs);
                if (ppOff) {
                    lastWirelessConnected = false;
                    // pp 通道明确断开（session 通道失败时的兜底）：清无线 CP/quick 状态
                    lastCpMode = null;
                    lastCpWorkMode = null;
                    lastWlsCpEvidence = false;
                    lastWlsWorkModeMs = null;
                    lastCurDecision = null;
                    lastCurDecisionKey = null;
                    lastQuickCurMax = null;
                    lastWlsSessionMs = null;
                } else if (ppNewSession) {
                    lastWirelessConnected = true;
                    // 真正的新无线会话边界：统一清空 wireless-session scoped 状态，
                    // 本轮 pp 有新证据再重新填（避免旧 Final 串进新会话）
                    lastWlsSessionMs = pgMs;
                    clearWirelessSessionState();
                }
                // quick wireless cur_max：只接受当前会话窗口内的值
                if (ppOff) {
                    lastQuickCurMax = null;
                } else if (pgMs != null && !pgMs.equals(lastWlsSessionMs)) {
                    // pp 窗口边界与会话 key 不一致：不采纳旧会话 cur_max
                } else {
                    String wt = splitAfterLastWirelessAttach(ppLog);
                    Integer qcm = parseQuickCurMax(wt);
                    if (qcm != null) lastQuickCurMax = qcm;
                }
                JSONObject cpState = parseSessionCpState(ppLog);
                // 有线输入遥测：只解析最后一次会话边界之后的日志段；
                // 同一行（log_time/vbus/ibus）不刷新 at，避免旧值被伪装成刚刚采到；
                // 新会话/协议变化后尚无遥测时清空缓存并标记等待，页面回退 USB uevent
                if (isWiredDisconnected(ppLog)) {
                    lastVoters = clearVoteTopics(lastVoters, WIRED_VOTER_TOPICS);
                    lastChgEnabled = null;
                    lastWiredQcTarget = null;
                    lastWiredQcTargetKey = null;
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
                // 无线 track：复用会话 key。同一 power_good_on 反复出现在窗口
                // 不重置 lastCurDecisionKey，避免 at 被“本次扫描时间”假刷新。
                if (ppOff) {
                    // 上面已清空
                } else if (ppNewSession) {
                    if (!cpState.isNull("w_mode")) {
                        lastCpMode = cpState.getInt("w_mode");
                        if (lastCpMode == 0) {
                            // 明确切到 Buck：清掉旧 work_mode，避免页面永远保持 CP
                            lastCpWorkMode = null;
                            lastWlsWorkModeMs = null;
                            lastWlsCpEvidence = false;
                        } else if (lastCpMode > 0) {
                            lastWlsCpEvidence = true;
                        }
                    }
                    if (!cpState.isNull("w_work")) {
                        lastCpWorkMode = cpState.getInt("w_work");
                        if (lastCpWorkMode == 1 || lastCpWorkMode == 2 || lastCpWorkMode == 4) {
                            lastWlsCpEvidence = true;
                        }
                        if (!cpState.isNull("w_work_ms")) {
                            lastWlsWorkModeMs = cpState.getLong("w_work_ms");
                        }
                    }
                    lastCurDecision = cpState.isNull("w_decision")
                            ? null : cpState.getJSONObject("w_decision");
                    lastCurDecisionKey = null;
                } else if (cpState.optBoolean("w_boundary", false)
                        && pgMs != null && !pgMs.equals(lastWlsSessionMs)) {
                    // pp 窗口里的边界与已确认会话不一致：不采纳本轮 CP/Final
                } else {
                    if (!cpState.isNull("w_mode")) {
                        lastCpMode = cpState.getInt("w_mode");
                        if (lastCpMode == 0) {
                            // 明确切到 Buck：清掉旧 work_mode，避免页面永远保持 CP
                            lastCpWorkMode = null;
                            lastWlsWorkModeMs = null;
                            lastWlsCpEvidence = false;
                        } else if (lastCpMode > 0) {
                            lastWlsCpEvidence = true;
                        }
                    }
                    if (!cpState.isNull("w_work")) {
                        lastCpWorkMode = cpState.getInt("w_work");
                        if (lastCpWorkMode == 1 || lastCpWorkMode == 2 || lastCpWorkMode == 4) {
                            lastWlsCpEvidence = true;
                        }
                        if (!cpState.isNull("w_work_ms")) {
                            lastWlsWorkModeMs = cpState.getLong("w_work_ms");
                        }
                    }
                    if (!cpState.isNull("w_decision")) {
                        JSONObject wd = cpState.getJSONObject("w_decision");
                        String key = wd.optString("log_time", "") + "|"
                                + wd.opt("final");
                        if (!key.equals(lastCurDecisionKey)) {
                            lastCurDecisionKey = key;
                            lastCurDecision = wd;
                        }
                    }
                }
                // 有线 track：usb online / real_type changed 边界
                if (cpState.optBoolean("d_boundary", false)) {
                    lastWiredState = cpState.optString("d_state", "unknown");
                    if (!cpState.isNull("d_boundary_at")) {
                        lastWiredSessionMs = cpState.getLong("d_boundary_at");
                    }
                    lastWiredCpRatio = cpState.isNull("d_ratio") ? null : cpState.getInt("d_ratio");
                    lastWiredCurCp = cpState.optBoolean("d_cur_cp", false);
                    lastWiredBuck = cpState.optBoolean("d_buck", false);
                    lastWiredCurMax = cpState.isNull("d_cur_max")
                            ? null : cpState.getJSONObject("d_cur_max");
                    lastWiredStageCurMax = cpState.isNull("d_stage_cur_max")
                            ? null : cpState.getJSONObject("d_stage_cur_max");
                    lastWiredQcTarget = cpState.isNull("d_qc_target")
                            ? null : cpState.getJSONObject("d_qc_target");
                    lastWiredCurMaxKey = null;
                    lastWiredStageCurMaxKey = null;
                    lastWiredQcTargetKey = null;
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
                    if (!cpState.isNull("d_cur_max")) {
                        JSONObject wcm = cpState.getJSONObject("d_cur_max");
                        String key = wcm.optString("log_time", "") + "|"
                                + wcm.opt("cur_max");
                        if (!key.equals(lastWiredCurMaxKey)) {
                            lastWiredCurMaxKey = key;
                            lastWiredCurMax = wcm;
                        }
                    }
                    if (!cpState.isNull("d_stage_cur_max")) {
                        JSONObject wsm = cpState.getJSONObject("d_stage_cur_max");
                        String key = wsm.optString("log_time", "") + "|"
                                + wsm.opt("cur_max");
                        if (!key.equals(lastWiredStageCurMaxKey)) {
                            lastWiredStageCurMaxKey = key;
                            lastWiredStageCurMax = wsm;
                        }
                    }
                    if (!cpState.isNull("d_qc_target")) {
                        JSONObject wqt = cpState.getJSONObject("d_qc_target");
                        String key = wqt.optString("log_time", "") + "|"
                                + wqt.opt("fcc") + "|" + wqt.opt("ibus");
                        if (!key.equals(lastWiredQcTargetKey)) {
                            lastWiredQcTargetKey = key;
                            lastWiredQcTarget = wqt;
                        }
                    }
                }
            }
            // chg_enable 是共享主题，但停充输入测量只能使用“当前有线会话”内、
            // 且仍有启用票的 effective；日志滑窗缺失/旧会话结果一律视为未知。
            long chgSessionAt = lastWiredSessionMs == null ? 0L : lastWiredSessionMs;
            lastChgEnabled = effectiveVoteValue(lastVoters, "chg_enable", chgSessionAt);
            // 三条通道独立 stale：功率路径失败不拖累 session/vote 主链路
            logsStale = !voteReadOk || !sessionReadOk;
            powerPathLogsStale = !ppReadOk;
            lastLogsUpdatedAt = System.currentTimeMillis();
            publishLogs();
        } catch (Exception e) {
            Log.w("ChargeDashboard", "collectLogs failed: " + e.getMessage());
        }
    }

    /** 无线会话级状态统一清空：真正的新 power_good_on 或断开时调用。
     *  lastWlsSessionMs 由调用方维护；这里只清 wireless-session scoped 数据。 */
    private void clearWirelessSessionState() {
        lastVoters = clearVoteTopics(lastVoters, WIRELESS_VOTER_TOPICS);
        lastWlsIcl = null;
        lastWlsIclAt = null;
        lastWlsIclLogTime = null;
        lastWlsIclMs = null;
        lastWlsIclKey = null;
        lastWlsChgEn = null;
        lastEpp = null;
        lastQuickCurMax = null;
        lastBuckFcc = null;
        lastCpMode = null;
        lastCpWorkMode = null;
        lastWlsCpEvidence = false;
        lastWlsWorkModeMs = null;
        lastCurDecision = null;
        lastCurDecisionKey = null;
        lastWlsMode = "unknown";
        lastRxIoutLimit = null;
        rxIoutLimitCaptured = false;
        lastRxIoutLimitAt = null;
        lastRxIoutLimitLogTime = null;
    }

    /** 按 topic 增量合并投票表；日志窗口缺少某个 topic 不等于该 topic 被撤票。 */
    static JSONObject mergeVoteTopics(JSONObject previous, JSONObject incoming) throws JSONException {
        JSONObject merged = previous == null ? new JSONObject()
                : new JSONObject(previous.toString());
        if (incoming == null) return merged;
        JSONArray names = incoming.names();
        if (names == null) return merged;
        for (int i = 0; i < names.length(); i++) {
            String topic = names.optString(i, "");
            JSONObject next = incoming.optJSONObject(topic);
            if (next == null) continue;
            JSONObject old = merged.optJSONObject(topic);
            if (old == null) {
                merged.put(topic, new JSONObject(next.toString()));
                continue;
            }
            JSONObject block = new JSONObject(old.toString());
            for (String field : new String[]{"topic", "time", "unit", "policy"}) {
                String value = next.optString(field, "");
                if (!value.isEmpty()) block.put(field, value);
            }
            long at = next.optLong("at", 0L);
            if (at > 0L) block.put("at", at);
            for (String field : new String[]{"changed", "result"}) {
                Object value = next.opt(field);
                if (value != null && value != JSONObject.NULL) {
                    block.put(field, value instanceof JSONObject
                            ? new JSONObject(value.toString()) : value);
                }
            }
            JSONArray rows = next.optJSONArray("rows");
            if (rows != null && rows.length() > 0) {
                block.put("rows", new JSONArray(rows.toString()));
            }
            merged.put(topic, block);
        }
        return merged;
    }

    /** 在明确的无线/有线会话边界清理对应域，避免旧会话票无限期残留。 */
    static JSONObject clearVoteTopics(JSONObject voters, java.util.Set<String> topics) {
        try {
            JSONObject cleared = voters == null ? new JSONObject()
                    : new JSONObject(voters.toString());
            if (topics != null) {
                for (String topic : topics) cleared.remove(topic);
            }
            return cleared;
        } catch (JSONException e) {
            return voters == null ? new JSONObject() : voters;
        }
    }

    /** 统一发布入口：快/慢采集都经此写 snapshotJson，避免互相覆盖。 */
    private synchronized void publishSnapshot(JSONObject core) throws JSONException {
        core.put("voters", lastVoters);
        core.put("sessions", lastSessions);
        if (lastEpp != null) appendEppNode(core, lastEpp);
        decorateWirelessPath(core);
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
                .put("cur_work_cp", lastWiredCurCp)
                .put("session_at", lastWiredSessionMs == null ? 0L : lastWiredSessionMs.longValue())
                .put("cur_max", lastWiredCurMax == null ? JSONObject.NULL : lastWiredCurMax)
                .put("stage_cur_max", lastWiredStageCurMax == null
                        ? JSONObject.NULL : lastWiredStageCurMax)
                .put("qc_target", lastWiredQcTarget == null
                        ? JSONObject.NULL : lastWiredQcTarget));
        // 统一刷新日志 meta，避免倒计时/失败标志延迟到下一轮快速采集
        JSONObject meta = core.optJSONObject("meta");
        if (meta == null) meta = new JSONObject();
        meta.put("interval", 3)
                .put("fast_interval", 3)
                .put("logs_interval", logsIntervalSeconds())
                .put("logs_updated_at", lastLogsUpdatedAt)
                .put("logs_stale", logsStale)
                .put("power_path_logs_stale", powerPathLogsStale)
                .put("adb", "root-direct")
                .put("version", BuildConfig.VERSION_NAME);
        core.put("meta", meta);
        publishJson(core);
    }

    /**
     * 发布独立的 wireless_path 派生对象。voters.wireless_buck_input 仅是
     * 投票详情兼容层；日志滑窗暂时没有该 topic 时，路径、比例、Final、ICL
     * 仍由当前无线会话状态继续发布。
     */
    private void decorateWirelessPath(JSONObject core) throws JSONException {
        JSONObject derived = core.optJSONObject("derived");
        if (derived == null) {
            derived = new JSONObject();
            core.put("derived", derived);
        }
        JSONObject path = new JSONObject();
        path.put("session_at", lastWlsSessionMs == null ? 0L : lastWlsSessionMs.longValue());
        if (lastWlsIcl != null) {
            path.put("wireless_icl", lastWlsIcl)
                    .put("wireless_icl_time", lastWlsIclLogTime == null ? "" : lastWlsIclLogTime)
                    .put("wireless_icl_at", lastWlsIclAt == null ? 0L : lastWlsIclAt.longValue())
                    .put("wireless_icl_ms", lastWlsIclMs == null ? 0L : lastWlsIclMs.longValue());
            if (lastWlsChgEn != null) path.put("wireless_icl_chg_en", lastWlsChgEn);
        }
        Integer actual = lastQuickCurMax != null ? lastQuickCurMax : lastBuckFcc;
        if (actual != null) {
            path.put("battery_limit_ma", actual)
                    .put("battery_limit_source",
                            lastQuickCurMax != null ? "quick_wireless cur_max" : "wireless loop buck_fcc");
        }
        double cpIbus = derived.optDouble("cp_ibus_total_ma", Double.NaN);
        boolean liveWirelessConnected = "wireless".equals(derived.optString("input_source"))
                && Double.isFinite(cpIbus);
        Integer evidenceMode = lastWlsCpEvidence ? lastCpMode : null;
        Integer evidenceWork = lastWlsCpEvidence ? lastCpWorkMode : null;
        String cpState;
        String cpSource;
        boolean cpActive;
        if (liveWirelessConnected) {
            String liveCpState = classifyWirelessCpIbus(cpIbus);
            cpState = resolveWirelessCpState(cpIbus, evidenceMode, evidenceWork);
            cpActive = "cp".equals(cpState);
            cpSource = !liveCpState.equals(cpState)
                    ? "sysfs_cp_ibus_total+session_cp_mode" : "sysfs_cp_ibus_total";
            path.put("cp_ibus_total_ma", cpIbus);
        } else if (evidenceWork != null
                && (evidenceWork == 1 || evidenceWork == 2 || evidenceWork == 4)) {
            cpState = "cp";
            cpActive = true;
            cpSource = "quick_wireless_work_mode";
        } else if (evidenceMode != null) {
            cpActive = evidenceMode > 0;
            cpState = cpActive ? "cp" : "buck";
            cpSource = "sc8581_operation_mode";
        } else {
            cpState = "unknown";
            cpActive = false;
            cpSource = "none";
        }
        path.put("state", cpState)
                .put("cp_active", cpActive)
                .put("cp_state_source", cpSource)
                .put("cp_session_evidence", lastWlsCpEvidence)
                .put("ratio", cpActive && evidenceWork != null ? evidenceWork : JSONObject.NULL)
                .put("wls_mode", lastWlsMode == null ? "unknown" : lastWlsMode)
                .put("rx_iout_limit", lastRxIoutLimit == null
                        ? JSONObject.NULL : lastRxIoutLimit.intValue())
                .put("rx_iout_limit_captured", rxIoutLimitCaptured)
                .put("rx_iout_limit_at", lastRxIoutLimitAt == null
                        ? 0L : lastRxIoutLimitAt.longValue())
                .put("rx_iout_limit_time", lastRxIoutLimitLogTime == null
                        ? "" : lastRxIoutLimitLogTime)
                .put("rx_iout_limit_stale", logsStale)
                .put("smartendura_soc_limit", lastSmartenduraSocLimit);
        if (lastWlsWorkModeMs != null) path.put("wls_work_mode_ms", lastWlsWorkModeMs.longValue());
        if (lastCurDecision != null) path.put("cur_max_decision", new JSONObject(lastCurDecision.toString()));
        derived.put("wireless_path", path);

        // 兼容旧前端/投票详情：topic 存在时镜像派生字段，但不再依赖它。
        JSONObject buck = core.getJSONObject("voters").optJSONObject("wireless_buck_input");
        if (buck != null) {
            JSONArray names = path.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i, "");
                    Object value = path.opt(name);
                    if (value != null) buck.put(name, value);
                }
            }
            if (path.has("wireless_icl")) {
                buck.put("icl", path.opt("wireless_icl"))
                        .put("icl_time", path.optString("wireless_icl_time", ""))
                        .put("icl_at", path.optLong("wireless_icl_at", 0L))
                        .put("icl_ms", path.optLong("wireless_icl_ms", 0L));
            }
            if (path.has("battery_limit_ma")) {
                buck.put("actual_limit", path.opt("battery_limit_ma"))
                        .put("actual_limit_source", path.optString("battery_limit_source", ""));
            }
            if (lastWlsChgEn != null) buck.put("chg_en", lastWlsChgEn);
        }
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
                    .put("meta", new JSONObject().put("interval", 3)
                            .put("adb", "root-direct").put("version", BuildConfig.VERSION_NAME));
        } catch (JSONException ignored) {}
        return o;
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
    }

    /** 一条 su 脚本批量读取 sysfs、uevent 与 thermal，###N 分隔。 */
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
                .append(USB_UEVENT).append("' 2>/dev/null; ")
                .append("echo '###").append(NODES.length + 2).append("'; tail -c 65536 '")
                .append(THERMAL_DUMP)
                .append("' 2>/dev/null | awk '")
                .append("/VIRTUAL-SENSOR-FORMULA/ { any=$0 } ")
                .append("/MONITOR-WIRELESS/ { wls=$0 } ")
                .append("END { if (wls != \"\") print wls; ")
                .append("if (any != \"\" && any != wls) print any }'");
        return RootShell.exec(sb.toString(), 20);
    }

    private void updateLogsActive(JSONObject data) {
        JSONObject battery = data.optJSONObject("battery");
        JSONObject status = battery == null ? null : battery.optJSONObject("status");
        String batteryStatus = status == null ? "" : status.optString("value", "");
        JSONObject derived = data.optJSONObject("derived");
        String source = derived == null ? "" : derived.optString("input_source", "");
        double vrect = derived == null ? Double.NaN : derived.optDouble("vrect", Double.NaN);
        boolean inputConnected = "wired".equals(source) || "wireless".equals(source)
                || (derived != null && derived.optBoolean("wired_online", false))
                || (Double.isFinite(vrect) && vrect > 0);
        logsActive = "Charging".equalsIgnoreCase(batteryStatus) || inputConnected;
    }

    private static final class LogBundle {
        String vote = "";
        String session = "";
        String powerPath = "";
        boolean voteOk;
        boolean sessionOk;
        boolean powerPathOk;
    }

    private static String section(String raw, String begin, String end) {
        int from = raw.indexOf(begin);
        if (from < 0) return "";
        from += begin.length();
        if (from < raw.length() && raw.charAt(from) == '\n') from++;
        int to = raw.indexOf(end, from);
        return (to < 0 ? raw.substring(from) : raw.substring(from, to)).trim();
    }

    /**
     * 一次 su 完成投票、最新会话与功率路径读取。
     * 会话最多扫描最新两个轮转文件，按旧→新过滤后由解析器按无线/有线边界
     * 选择最后一个会话；兼顾日志轮转边界与“只展示最新会话”。
     */
    private LogBundle readLogs() {
        String grepArgs = "-e 'power_good' -e 'usb online' -e 'real_type changed' "
                + "-e 'AUTHEN_FINISH' -e 'uuid_value' "
                + "-e 'TX_ADAPTER' -e 'FAST_CHARGE' -e 'fast chg success' "
                + "-e 'set chg current' -e 'open path ibus' "
                + "-e 'sc8581_set_operation_mode' -e 'mca_quick_charge_update_work_mode_para' "
                + "-e 'strategy_quickchg_map_ibus_to_fsw' -e 'cur_work_cp' "
                + "-e 'strategy_quickchg_enable_buck_charging' "
                + "-e 'smartchg_soc_limit_callback' "
                + "-e 'strategy_wireless_get_qc_enable' "
                + "-e 'strategy_wireless_get_charging_info' "
                + "-e 'BPP drawload' -e 'rx_iout_limit' -e 'epp plus' "
                + "-e 'EPP+' -e 'send_vout_range_request' -e 'set adapter voltage'";
        // 全部是字面量；通用 mca_quick_charge_select_max_ibat 已覆盖原先两个 .* 分支。
        String ppGrepArgs = "-e 'power_good' -e 'usb online' -e 'real_type changed' "
                + "-e 'sc8581_set_operation_mode' "
                + "-e 'mca_quick_charge_update_work_mode_para' "
                + "-e 'strategy_quickchg_map_ibus_to_fsw' -e 'cur_work_cp' "
                + "-e 'strategy_buckchg_charge_limit' "
                + "-e 'strategy_buckchg_update_charge_status' "
                + "-e 'mca_quick_charge_regulation' "
                + "-e 'mca_wireless_quick_charge_select_cur_work_mode' "
                + "-e 'mca_wireless_quick_charge_select_max_ibat' "
                + "-e 'target_limit_fcc_ma' "
                + "-e 'mca_quick_charge_select_max_ibat'";
        String dir = MCA_LOG_DIR + "/";
        // mca_vote 只在投票变化时打印。前台连续运行时按文件偏移增量读取，
        // 每次最多补读 256KiB（含 64KiB 重叠）；进程重启、日志轮转或
        // 间隔过大时直接回退到最新 2MiB，不追赶无限历史。
        boolean allowOlderVoteFallback = lastVoters.length() == 0;
        String prevVoteFile = lastVoteFile == null ? "" : lastVoteFile;
        if (!prevVoteFile.matches("[A-Za-z0-9_.\\-]*")) prevVoteFile = "";
        long prevVoteOffset = Math.max(0L, lastVoteOffset);
        String votePath = dir + "${newest}";
        String voteRead =
                "vote_size=$(stat -c %s " + votePath + " 2>/dev/null || "
                + "wc -c < " + votePath + "); "
                + "vote_mode=tail; "
                + "if [ \"$newest\" = '" + prevVoteFile + "' ] && [ \"$vote_size\" -ge "
                + prevVoteOffset + " ] && [ $((vote_size - " + prevVoteOffset + ")) -le "
                + VOTE_INCREMENT_MAX_BYTES + " ]; then vote_mode=incremental; fi; "
                + "echo '__VOTE_CURSOR__${newest}|${vote_size}|${vote_mode}'; ";
        if (allowOlderVoteFallback) {
            voteRead += "[ -n \"${older}\" ] && tail -c " + VOTE_FALLBACK_BYTES + " " + dir
                    + "${older} 2>/dev/null | grep -a -F 'mca_vote'; ";
        }
        voteRead +=
                "if [ \"$vote_mode\" = incremental ]; then "
                + "vote_start=$((" + prevVoteOffset + " - " + VOTE_OVERLAP_BYTES + ")); "
                + "[ $vote_start -lt 0 ] && vote_start=0; "
                + "vote_aligned=$((vote_start / 4096 * 4096)); "
                + "vote_skip=$((vote_aligned / 4096)); "
                + "vote_prefix=$((vote_start - vote_aligned)); "
                + "vote_count=$((vote_size - vote_start)); "
                + "dd if=" + votePath + " bs=4096 skip=$vote_skip 2>/dev/null "
                + "| tail -c +$((vote_prefix + 1)) | head -c $vote_count "
                + "| grep -a -F 'mca_vote'; "
                + "else tail -c " + VOTE_FALLBACK_BYTES + " " + votePath
                + " 2>/dev/null | grep -a -F 'mca_vote'; fi; ";
        String script = "set -- $(ls -t " + MCA_LOG_DIR + " 2>/dev/null | head -n 2); "
                + "newest=$1; older=$2; "
                + "echo __LOG_FILE__${newest}; "
                + "echo __VOTE_BEGIN__; "
                + voteRead + "echo __VOTE_END__; "
                + "echo __SESSION_BEGIN__; "
                + "{ [ -n \"${older}\" ] && tail -c 4194304 " + dir + "${older} 2>/dev/null "
                + "| grep -a -F " + grepArgs + " | grep -v -F 'sysfs_show'; "
                + "tail -c 4194304 " + dir + "${newest} 2>/dev/null "
                + "| grep -a -F " + grepArgs + " | grep -v -F 'sysfs_show'; }; "
                + "echo __SESSION_END__; "
                + "echo __PP_BEGIN__; "
                + "tail -c 1048576 " + dir + "${newest} 2>/dev/null "
                + "| grep -a -F " + ppGrepArgs + " | tail -n 200; echo __PP_END__";
        String raw = RootShell.exec(script, 25);
        LogBundle out = new LogBundle();
        if (raw.isEmpty()) return out;
        Matcher fm = Pattern.compile("__LOG_FILE__([A-Za-z0-9_.\\-]+)").matcher(raw);
        boolean fileOk = fm.find();
        if (fileOk) lastLogFname = fm.group(1);
        Matcher vm = Pattern.compile("__VOTE_CURSOR__([A-Za-z0-9_.\\-]+)\\|(\\d+)\\|(incremental|tail)")
                .matcher(raw);
        if (vm.find()) {
            lastVoteFile = vm.group(1);
            lastVoteOffset = Long.parseLong(vm.group(2));
            voteCursorReady = true;
        }
        out.voteOk = fileOk && raw.contains("__VOTE_BEGIN__") && raw.contains("__VOTE_END__");
        out.sessionOk = fileOk && raw.contains("__SESSION_BEGIN__") && raw.contains("__SESSION_END__");
        out.powerPathOk = fileOk && raw.contains("__PP_BEGIN__") && raw.contains("__PP_END__");
        out.vote = section(raw, "__VOTE_BEGIN__", "__VOTE_END__");
        out.session = section(raw, "__SESSION_BEGIN__", "__SESSION_END__");
        out.powerPath = section(raw, "__PP_BEGIN__", "__PP_END__");
        return out;
    }

    private JSONObject build(String batch) throws JSONException {
        // 解析 ###N 块
        String[] raw = batch.split("(?=###\\d+)");
        JSONObject nodesObj = new JSONObject();
        JSONObject batteryRaw = new JSONObject();
        JSONObject usbRaw = new JSONObject();
        String thermalRaw = "";
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
            } else if (i == NODES.length + 2) {
                thermalRaw = body;
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
        String usbOnlineRaw = usbRaw.optString("ONLINE", "");
        Boolean usbOnlineState = "1".equals(usbOnlineRaw) ? Boolean.TRUE
                : "0".equals(usbOnlineRaw) ? Boolean.FALSE : null;
        boolean usbOnline = Boolean.TRUE.equals(usbOnlineState);
        boolean usbKnownOff = Boolean.FALSE.equals(usbOnlineState);
        double usbVbusMv = optNum(usbRaw, "VOLTAGE_NOW") / 1000.0;
        double usbIbusMa = optNum(usbRaw, "CURRENT_NOW") / 1000.0;
        double cpIbusTotalMa = nodeNum(nodesObj.optJSONObject("ibus_total"));
        // SIC-BAT 的实时 PID 输出是独立于 div/buck votable 的有线热控层。
        // 节点原始单位为 µA；0 表示当前未施加可用的 SIC 上限，不能拿来压低结果。
        double wiredSicLimitMa = nodeNum(nodesObj.optJSONObject("wired_chg_curr")) / 1000.0;
        if (Double.isFinite(wiredSicLimitMa) && wiredSicLimitMa > 0.0) {
            putFinite(derived, "wired_sic_limit_ma", wiredSicLimitMa);
        }
        boolean wirelessSignal = Double.isFinite(vout) && Double.isFinite(iout)
                && vout > 1000.0 && iout > 100.0;
        // ONLINE=0 是有线硬否决；只有字段未知时才允许 VBUS 回退。
        // ibus_total 是有线/无线 CP 共用测量，绝不能参与输入源判定。
        String inputSource = InputSourceResolver.resolve(
                usbOnlineState, usbVbusMv, wirelessSignal);
        boolean wiredPresent = "wired".equals(inputSource);
        boolean wirelessConnected = InputSourceResolver.resolveWirelessConnected(
                lastWirelessConnected, inputSource, vout);

        JSONObject cpTel = lastWiredCpTel;
        JSONObject buckTel = lastWiredBuckTel;
        JSONObject chosen;
        String wstate = lastWiredState == null ? "unknown" : lastWiredState;
        if ("cp".equals(wstate)) {
            chosen = cpTel != null ? cpTel : buckTel;
        } else if ("buck".equals(wstate)) {
            chosen = buckTel != null ? buckTel : cpTel;
        } else if (cpTel != null && buckTel != null) {
            chosen = cpTel.optLong("at", 0L) >= buckTel.optLong("at", 0L)
                    ? cpTel : buckTel;
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
        // 策略日志遥测（校验数据）：regulation/buckchg 的 vbus/ibus，不再承担实时曲线
        double telVbusMv = Double.NaN;
        double telIbusMa = Double.NaN;
        String telSource = null;
        String telLogTime = "";
        long telAt = 0L;
        if (chosen != null && !usbKnownOff && !(wiredStale && usbOnline)) {
            telVbusMv = chosen.optDouble("vbus_mv", Double.NaN);
            telIbusMa = chosen.optDouble("ibus_ma", Double.NaN);
            telSource = chosen.optString("source", null);
            telLogTime = chosen.optString("log_time", "");
            telAt = chosen.optLong("at", 0L);
        }
        // 实时测量主源（每 3s）：
        //  有线 CP         → ibus_total（电荷泵总线电流，sysfs）
        //  有线 Buck/unknown → usb uevent CURRENT_NOW
        //  两者都不可用     → 回退策略日志遥测
        double rtVbusMv = Double.NaN;
        double rtIbusMa = Double.NaN;
        String rtSource = null;
        long rtAt = 0L;
        String preferredWiredSource = InputSourceResolver.resolveWiredInputSource(
                wstate, cpIbusTotalMa, usbIbusMa, usbOnline,
                lastChgEnabled == null ? null : lastChgEnabled == 1,
                battCurMa);
        if ("cp_ibus_total".equals(preferredWiredSource) && wiredPresent) {
            if (Double.isFinite(cpIbusTotalMa)) {
                double vb = Double.isFinite(telVbusMv) ? telVbusMv : usbVbusMv;
                if (Double.isFinite(vb)) {
                    rtVbusMv = vb;
                    rtIbusMa = cpIbusTotalMa;
                    rtSource = "cp_ibus_total";
                    rtAt = nowMs;
                }
            }
        }
        if (rtSource == null && usbOnline && Double.isFinite(usbVbusMv)
                && Double.isFinite(usbIbusMa)) {
            rtVbusMv = usbVbusMv;
            rtIbusMa = usbIbusMa;
            rtSource = "usb_uevent";
            rtAt = nowMs;
        }
        if (rtSource == null && wiredPresent
                && Double.isFinite(telVbusMv) && Double.isFinite(telIbusMa)) {
            rtVbusMv = telVbusMv;
            rtIbusMa = telIbusMa;
            rtSource = telSource;
            rtAt = telAt;
        }
        boolean wiredOnline = wiredPresent && Double.isFinite(rtVbusMv)
                && rtVbusMv > 1000.0 && Double.isFinite(rtIbusMa);
        // mV × mA = µW，直接换算成 W（除以 1e6），前端只显示 W
        double wiredPower = wiredOnline ? rtVbusMv * rtIbusMa / 1e6 : Double.NaN;

        // 当前输入源抽象层：无线已有有效 RX 电流时，不能让拔线瞬间残留的
        // USB ONLINE/旧 CP 遥测继续把页面锁在有线 CP 分支。
        double inputVolMv = Double.NaN;
        double inputCurMa = Double.NaN;
        double inputPower = Double.NaN;
        if ("wireless".equals(inputSource)) {
            inputVolMv = vout;
            inputCurMa = iout;
            inputPower = vout * iout / 1e6;
        } else if ("wired".equals(inputSource)) {
            inputVolMv = rtVbusMv;
            inputCurMa = rtIbusMa;
            inputPower = wiredPower;
        }

        derived.put("input_source", inputSource);
        derived.put("wireless_connected", wirelessConnected);
        derived.put("wired_connected", wiredPresent);
        derived.put("input_connected", wiredPresent || wirelessConnected);
        derived.put("input_detail_source",
                "wired".equals(inputSource) ? (rtSource == null ? JSONObject.NULL : rtSource)
                        : "wireless".equals(inputSource) ? "wls_debug" : JSONObject.NULL);
        putFinite(derived, "input_power_w", inputPower);
        putFinite(derived, "input_voltage_mv", inputVolMv);
        putFinite(derived, "input_current_ma", inputCurMa);
        derived.put("wired_online", wiredOnline);
        putFinite(derived, "wired_vbus_mv", rtVbusMv);
        putFinite(derived, "wired_ibus_ma", rtIbusMa);
        putFinite(derived, "wired_input_power_w", wiredPower);
        derived.put("wired_input_source", rtSource == null ? JSONObject.NULL : rtSource);
        if (rtAt == 0L) {
            derived.put("wired_input_at", JSONObject.NULL);
        } else {
            derived.put("wired_input_at", rtAt);
        }
        derived.put("wired_input_log_time", telLogTime);
        long wiredAge = rtAt == 0L ? -1L : (nowMs - rtAt) / 1000;
        if (wiredAge < 0) {
            derived.put("wired_input_age", JSONObject.NULL);
        } else {
            derived.put("wired_input_age", wiredAge);
        }
        derived.put("wired_input_waiting",
                lastWiredTelWaiting && chosen == null && usbOnline);
        derived.put("wired_input_stale", wiredStale);
        // 校验遥测（策略日志）：与实时曲线源分层展示
        derived.put("wired_tel_source", telSource == null ? JSONObject.NULL : telSource);
        putFinite(derived, "wired_tel_vbus_mv", telVbusMv);
        putFinite(derived, "wired_tel_ibus_ma", telIbusMa);
        derived.put("wired_tel_stale", wiredStale);
        derived.put("wired_tel_log_time", telLogTime);
        derived.put("wired_tel_at", telAt == 0L ? JSONObject.NULL : telAt);
        derived.put("wired_usb_online", usbOnline);
        // 每 3 秒读取的实时 CP 总线电流；用于日志未打印模式行时判定无线 Buck/CP。
        putFinite(derived, "cp_ibus_total_ma", cpIbusTotalMa);
        derived.put("cp_ibus_owner",
                InputSourceResolver.cpIbusOwner(inputSource, cpIbusTotalMa));
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

        JSONObject thermal = parseThermalDump(thermalRaw);
        if (!thermal.isNull("virtual_temp")) {
            lastThermal = new JSONObject(thermal.toString());
        } else if (!lastThermal.isNull("virtual_temp")) {
            thermal = new JSONObject(lastThermal.toString());
        }

        return new JSONObject()
                .put("ts", System.currentTimeMillis() / 1000.0)
                .put("iso", isoNow())
                .put("nodes", nodeList)
                .put("battery", battery)
                .put("derived", derived)
                .put("thermal", thermal)
                .put("meta", new JSONObject()
                        .put("interval", 3)
                        .put("fast_interval", 3)
                        .put("logs_interval", logsIntervalSeconds())
                        .put("logs_updated_at", lastLogsUpdatedAt)
                        .put("logs_stale", logsStale)
                        .put("power_path_logs_stale", powerPathLogsStale)
                        .put("adb", "root-direct")
                        .put("version", BuildConfig.VERSION_NAME));
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

    private static String classifyWirelessCpIbus(double cpIbusMa) {
        double current = Math.abs(cpIbusMa);
        if (current >= CP_IBUS_ACTIVE_MIN_MA) return "cp";
        if (current <= CP_IBUS_BUCK_MAX_MA) return "buck";
        return "transition";
    }

    private static String resolveWirelessCpState(
            double cpIbusMa, Integer cpMode, Integer cpWorkMode) {
        String live = classifyWirelessCpIbus(cpIbusMa);
        boolean sessionCp = (cpMode != null && cpMode > 0)
                || (cpMode == null && cpWorkMode != null
                && (cpWorkMode == 1 || cpWorkMode == 2 || cpWorkMode == 4));
        return "buck".equals(live) && sessionCp ? "cp" : live;
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

    private static final Pattern LOG_FILE_RE =
            Pattern.compile("mca_log_(\\d{2})(\\d{2})_(\\d{2})(\\d{2})\\.log");

    /** 日志行时间（已 shift 为本地）→ 归一化绝对毫秒：文件名日期 + 行内时刻，跨文件单调。 */
    private long absLogMs(String fname, String hms) {
        java.time.LocalDate d = java.time.LocalDate.now();
        Matcher fm = LOG_FILE_RE.matcher(fname == null ? "" : fname);
        boolean hasFileTime = fm.find();
        if (hasFileTime) {
            d = java.time.LocalDate.of(d.getYear(),
                    Integer.parseInt(fm.group(1)), Integer.parseInt(fm.group(2)));
        }
        long base = d.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        String[] p = hms.split(":");
        if (p.length < 4) return base;
        try {
            long eventMs = Long.parseLong(p[0]) * 3600000L
                    + Long.parseLong(p[1]) * 60000L
                    + Long.parseLong(p[2]) * 1000L + Long.parseLong(p[3]);
            if (hasFileTime) {
                long fileStartMs = Integer.parseInt(fm.group(3)) * 3600000L
                        + Integer.parseInt(fm.group(4)) * 60000L;
                if (eventMs + 12L * 3600000L < fileStartMs) {
                    base += 24L * 3600000L;
                }
            }
            return base + eventMs;
        } catch (NumberFormatException e) {
            return base;
        }
    }

    /** 日志事件优先使用日志本身的绝对时间；无时间戳时才回退采集时刻。 */
    private long logEventAt(String logTime) {
        return logTime == null || logTime.isEmpty()
                ? System.currentTimeMillis() : absLogMs(lastLogFname, logTime);
    }

    /** sysfs 节点数值：非法/空返回 NaN。 */
    private static double nodeNum(JSONObject node) {
        if (node == null || !node.optBoolean("ok", false)) return Double.NaN;
        try {
            return Double.parseDouble(node.optString("value").trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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
        // 项目假设（用户确认，未从 .ko 独立核实）：有线 buck 充电电流按 MIN 推算，
        // 无 effective 行时只允许“参考推算”，不进入总仲裁 fallback
        VOTE_POLICIES.put("buck_charge_curr", "MIN_ASSUMED");
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
                long rms = time.isEmpty() ? 0L : absLogMs(lastLogFname, time);
                resultsByTopic.put(m.group(1), new JSONObject()
                        .put("topic", m.group(1)).put("value", Integer.parseInt(m.group(2)))
                        .put("client", m.group(3)).put("idx", Integer.parseInt(m.group(4)))
                        .put("time", time).put("r_ms", rms));
                continue;
            }
            m = VOTE_HEADER_RE.matcher(line);
            if (m.find()) {
                String topic = m.group(1);
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String time = tm.find() ? shiftLogTime(tm.group(1)) : "";
                long at = time.isEmpty() ? 0L : absLogMs(lastLogFname, time);
                current = new JSONObject()
                        .put("topic", topic).put("time", time).put("at", at)
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
        // 结果/变动行经常位于 VOTER 表头之后；解析结束后再回填，避免 topic
        // 只保留表头时丢掉当前 effective。日志窗口滚动时由 mergeVoteTopics 续接旧 topic。
        JSONArray topicNames = blocks.names();
        if (topicNames != null) {
            for (int i = 0; i < topicNames.length(); i++) {
                String topic = topicNames.optString(i, "");
                JSONObject block = blocks.optJSONObject(topic);
                if (block == null) continue;
                JSONObject changed = changesByTopic.get(topic);
                JSONObject result = resultsByTopic.get(topic);
                if (changed != null) block.put("changed", changed);
                if (result != null) block.put("result", result);
            }
        }
        return blocks;
    }

    private static Integer effectiveVoteValue(JSONObject voters, String topic, long sessionAt) {
        JSONObject block = voters.optJSONObject(topic);
        if (block == null || sessionAt <= 0L) return null;
        JSONArray rows = block.optJSONArray("rows");
        boolean hasEnabled = false;
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                if (rows.optJSONObject(i) != null
                        && rows.optJSONObject(i).optInt("enable", 0) == 1) {
                    hasEnabled = true;
                    break;
                }
            }
        }
        if (!hasEnabled) return null;
        JSONObject result = block.optJSONObject("result");
        if (result == null || !result.has("value")) return null;
        long resultAt = result.optLong("r_ms", block.optLong("at", 0L));
        if (resultAt <= 0L || resultAt < sessionAt) return null;
        return result.optInt("value");
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
    private static final Pattern REAL_TYPE_OFF_RE = Pattern.compile("real_type changed: \\d+ => 0");
    private static final Pattern REAL_TYPE_CHANGE_RE = Pattern.compile("real_type changed: (\\d+) => (\\d+)");
    private static final Pattern SESSION_CP_MODE_RE = Pattern.compile("sc8581_set_operation_mode:\\d+ .*set operation mode (\\d+) reg (\\d+) work_mode (\\d+)");
    private static final Pattern CP_RATIO_RE = Pattern.compile("strategy_quickchg_map_ibus_to_fsw:\\d+ .*ibus_avg: (\\d+), ratio: (\\d+), cp_iout: (\\d+)");
    private static final Pattern CP_ACTIVE_RE = Pattern.compile("mca_quick_charge_select_max_ibat:\\d+ .*cur_stage (\\d+) cur_max (\\d+) delta_cur (\\d+) cur_work_cp");
    private static final Pattern BUCK_PARALLEL_RE = Pattern.compile("strategy_quickchg_enable_buck_charging:\\d+ (enable|disable) buck parallel charging!.*?ibus\\s*: ?(\\d+)");

    private JSONArray parseSessions(String text) throws JSONException {
        JSONArray sessions = new JSONArray();
        JSONObject cur = null;
        JSONObject openGroup = null;
        JSONObject ichgGroup = null;
        int openFirstIbus = 0;
        int openCount = 0;
        int ichgFirstMa = 0;
        int ichgCount = 0;
        StringBuilder ichgSequence = new StringBuilder();
        String lastCpModeEvent = null;
        Integer lastCpRatioEvent = null;
        Integer lastCpStageEvent = null;
        Boolean lastBuckParallelEvent = null;
        for (String line : text.split("\n")) {
            String kind = null, detail = "";
            if (line.contains("wireless power_good_off")) kind = "off";
            else if (line.contains("wireless power_good_on")) kind = "on";
            else if (line.contains("usb online: 0") || REAL_TYPE_OFF_RE.matcher(line).find()) kind = "wired_off";
            else if (line.contains("usb online: 1")) kind = "wired_on";
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
            // 功率路径信号不是会话边界，但要进入会话时间线；原先只留在 PP 派生状态。
            if (kind == null) {
                Matcher rt = REAL_TYPE_CHANGE_RE.matcher(line);
                if (rt.find() && !"0".equals(rt.group(2))) {
                    kind = "wired_type";
                    detail = rt.group(1) + "→" + rt.group(2);
                }
            }
            if (kind == null) {
                Matcher pm = SESSION_CP_MODE_RE.matcher(line);
                if (pm.find()) {
                    kind = "cp_mode";
                    detail = "operation_mode " + pm.group(1) + " · work_mode " + pm.group(3);
                }
            }
            if (kind == null) {
                Matcher pr = CP_RATIO_RE.matcher(line);
                if (pr.find()) {
                    kind = "cp_ratio";
                    detail = "ratio " + pr.group(2) + ":1 · ibus_avg " + pr.group(1)
                            + "mA · cp_iout " + pr.group(3) + "mA";
                }
            }
            if (kind == null) {
                Matcher ca = CP_ACTIVE_RE.matcher(line);
                if (ca.find()) {
                    kind = "cp_active";
                    detail = "stage " + ca.group(1) + " · cur_max " + ca.group(2)
                            + "mA · delta " + ca.group(3) + "mA";
                }
            }
            if (kind == null) {
                Matcher bp = BUCK_PARALLEL_RE.matcher(line);
                if (bp.find()) {
                    kind = "enable".equals(bp.group(1)) ? "buck_parallel_on" : "buck_parallel_off";
                    detail = "ibus " + bp.group(2) + "mA";
                }
            }
            if (kind == null) continue;
            if (kind.equals("on") || kind.equals("wired_on")) {
                String source = kind.equals("wired_on") ? "wired" : "wireless";
                if (cur != null && !cur.optBoolean("ended")
                        && source.equals(cur.optString("source"))) continue;
                if (cur != null && !cur.optBoolean("ended")) cur.put("ended", true);
                openGroup = null;
                openCount = 0;
                ichgGroup = null;
                ichgCount = 0;
                ichgSequence.setLength(0);
                lastCpModeEvent = null;
                lastCpRatioEvent = null;
                lastCpStageEvent = null;
                lastBuckParallelEvent = null;
                cur = new JSONObject().put("start", shiftLogTime(timeOf(line))).put("ended", false)
                        .put("source", source)
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
            if (!kind.equals("open")) {
                openGroup = null;
                openCount = 0;
            }
            if (kind.equals("on")) {
                ev.put(event(line, "on", "无线充电板接入", ""));
            } else if (kind.equals("wired_on")) {
                ev.put(event(line, "wired_on", "有线充电接入", ""));
            } else if (kind.equals("wired_type")) {
                ev.put(event(line, "wired_type", "有线协议变化", detail));
            } else if (kind.equals("cp_mode")) {
                if (!detail.equals(lastCpModeEvent)) {
                    ev.put(event(line, "cp_mode", "CP 模式信号", detail));
                    lastCpModeEvent = detail;
                }
            } else if (kind.equals("cp_ratio")) {
                Matcher rm = Pattern.compile("ratio (\\d+):1").matcher(detail);
                int ratio = rm.find() ? Integer.parseInt(rm.group(1)) : -1;
                if (lastCpRatioEvent == null || ratio != lastCpRatioEvent) {
                    ev.put(event(line, "cp_ratio", "CP 分压比", detail));
                    lastCpRatioEvent = ratio;
                }
            } else if (kind.equals("cp_active")) {
                Matcher sm = Pattern.compile("stage (\\d+)").matcher(detail);
                int stage = sm.find() ? Integer.parseInt(sm.group(1)) : -1;
                if (lastCpStageEvent == null || stage != lastCpStageEvent) {
                    ev.put(event(line, "cp_active", "CP 充电路径运行", detail));
                    lastCpStageEvent = stage;
                }
            } else if (kind.equals("buck_parallel_on") || kind.equals("buck_parallel_off")) {
                boolean enabled = kind.equals("buck_parallel_on");
                if (lastBuckParallelEvent == null || lastBuckParallelEvent != enabled) {
                    ev.put(event(line, kind, enabled ? "Buck 并行充电启用" : "Buck 并行充电关闭", detail));
                    lastBuckParallelEvent = enabled;
                }
            } else if (kind.equals("off") || kind.equals("wired_off")) {
                String source = cur.optString("source");
                boolean matches = (kind.equals("off") && "wireless".equals(source))
                        || (kind.equals("wired_off") && "wired".equals(source));
                if (!matches) continue;
                cur.put("ended", true);
                ev.put(event(line, kind, "wired".equals(source) ? "有线充电移除" : "无线充电板移除", ""));
                cur = null;
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
                int v = Integer.parseInt(detail);
                if (ichgGroup == null) {
                    ichgFirstMa = v;
                    ichgCount = 1;
                    ichgSequence.setLength(0);
                    ichgSequence.append(v);
                    ichgGroup = event(line, "ichg", "设置充电电流", v + "mA");
                    ev.put(ichgGroup);
                } else {
                    ichgCount++;
                    ichgSequence.append("→").append(v);
                    ichgGroup.put("detail", ichgSequence + "mA · " + ichgCount + "次");
                }
                if (cur.isNull("peak_limit_ma") || v > cur.optInt("peak_limit_ma"))
                    cur.put("peak_limit_ma", v);
                cur.put("final_limit_ma", v);
            } else if (kind.equals("open")) {
                cur.put("opens", cur.optInt("opens") + 1);
                int ibus = Integer.parseInt(detail);
                if (openGroup == null) {
                    openFirstIbus = ibus;
                    openCount = 1;
                    openGroup = event(line, "open", "CP 建链", "ibus " + ibus + "mA");
                    ev.put(openGroup);
                } else {
                    openCount++;
                    openGroup.put("detail", "ibus " + openFirstIbus + "→" + ibus
                            + "mA · " + openCount + "次");
                }
            } else if (kind.equals("smart")) {
                cur.put("smartendura", true);
                ev.put(event(line, "smart", "SmartEndura 介入", ""));
            }
        }
        // 限制会话数量与事件数量，避免长期运行后 DOM/内存膨胀
        while (sessions.length() > SESSION_MAX) sessions.remove(0);
        for (int i = 0; i < sessions.length(); i++) {
            JSONArray evs = sessions.getJSONObject(i).getJSONArray("events");
            sortSessionEvents(evs);
            while (evs.length() > SESSION_EVENT_MAX) evs.remove(0);
        }
        return sessions;
    }

    /** 聚合电流序列时事件对象会更新到最后一次写入；重新按日志时间排列，避免路径行倒序。 */
    private void sortSessionEvents(JSONArray events) throws JSONException {
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) ordered.add(events.getJSONObject(i));
        ordered.sort((a, b) -> a.optString("time", "").compareTo(b.optString("time", "")));
        while (events.length() > 0) events.remove(0);
        for (JSONObject event : ordered) events.put(event);
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

    /** wireless loop icl 快照：值 + chg_en + 日志本地时间 + 解析时刻（用于判断新旧）。 */
    private static final class WlsIcl {
        final int value;
        final int chgEn;
        final long at;
        final String logTime;
        final long ms;

        WlsIcl(int value, int chgEn, long at, String logTime, long ms) {
            this.value = value;
            this.chgEn = chgEn;
            this.at = at;
            this.logTime = logTime;
            this.ms = ms;
        }
    }

    private static final Pattern WLS_ICL_RE =
            Pattern.compile("wireless loop: icl:(\\d+), buck_fcc:\\d+, chg_en:(\\d+)");
    private static final Pattern QUICK_CUR_MAX_RE =
            Pattern.compile("cur_max:\\[Final\\]: (\\d+)");
    private static final Pattern BUCK_FCC_RE =
            Pattern.compile("wireless loop: icl:\\d+, buck_fcc:(\\d+)");

    /** 最后一次无线电源事件是否为断开（power_good_off 晚于 power_good_on）。 */
    private boolean isLastWirelessPowerOff(String text) {
        return text.lastIndexOf("wireless power_good_off")
                > text.lastIndexOf("wireless power_good_on");
    }

    /** 取最新 wireless loop icl + chg_en（驱动实际下发状态），附带时间和采集时刻。 */
    private WlsIcl parseWlsIcl(String text) {
        WlsIcl last = null;
        for (String line : text.split("\n")) {
            Matcher m = WLS_ICL_RE.matcher(line);
            if (!m.find()) continue;
            Matcher tm = VOTE_TIME_RE.matcher(line);
            String raw = tm.find() ? tm.group(1) : "";
            String logTime = raw.isEmpty() ? "" : shiftLogTime(raw);
            long ms = raw.isEmpty() ? 0L : absLogMs(lastLogFname, logTime);
            last = new WlsIcl(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    ms > 0L ? ms : System.currentTimeMillis(), logTime, ms);
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
    private static final Pattern WIRED_STAGE_CUR_MAX_RE = Pattern.compile(
            "mca_quick_charge_select_max_ibat:.*cur_stage (\\d+) cur_max (\\d+) delta_cur (\\d+) cur_work_cp");
    private static final Pattern WIRED_FINAL_CUR_MAX_RE = Pattern.compile(
            "mca_quick_charge_select_max_ibat:.*cur_max (\\d+) secure_cur (\\d+) channel_cur (\\d+) thermal_cur (\\d+)");
    private static final Pattern WIRED_QC_TARGET_RE = Pattern.compile(
            "target_limit_fcc_ma:\\s*(\\d+)\\s*,\\s*target_limit_ibus_ma:\\s*(\\d+)");
    private static final Pattern WIRED_OPERATION_RE = Pattern.compile(
            "sc8581_set_operation_mode:.*work_mode\\s+(\\d+)");
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
                String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
                inputs = new JSONObject()
                        .put("channel_cur", Integer.parseInt(m.group(1)))
                        .put("temp_max_cur", Integer.parseInt(m.group(2)))
                        .put("tx_adapter_max", Integer.parseInt(m.group(3)))
                        .put("sw_qc_ichg", Integer.parseInt(m.group(4)))
                        .put("sw_thermal_ichg", Integer.parseInt(m.group(5)))
                        .put("log_time", logTime)
                        .put("at", logEventAt(logTime));
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
            String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
            last = new JSONObject()
                    .put("vbus_mv", Integer.parseInt(m.group(7)) / 1000.0)
                    .put("ibus_ma", Integer.parseInt(m.group(8)) / 1000.0)
                    .put("chg_en", Integer.parseInt(m.group(3)))
                    .put("chg_en_client", m.group(4))
                    .put("chg_type", Integer.parseInt(m.group(5)))
                    .put("source", "buckchg_telemetry")
                    .put("log_time", logTime)
                    .put("at", logEventAt(logTime));
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
            String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
            last = new JSONObject()
                    .put("vbus_mv", Integer.parseInt(m.group(3)))
                    .put("ibus_ma", Integer.parseInt(m.group(10)))
                    .put("chg_en", 1)
                    .put("chg_en_client", "quick_charge")
                    .put("source", "quick_charge_regulation")
                    .put("log_time", logTime)
                    .put("at", logEventAt(logTime));
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

    private static final Pattern RX_IOUT_LIMIT_RE =
            Pattern.compile("rx_iout_limit:\\s*(\\d+)");

    /** 只保留最后一次 wireless power_good_on 之后的日志段。 */
    private String splitAfterLastWirelessAttach(String text) {
        String[] lines = text.split("\n");
        int last = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("power_good_on")) last = i;
        }
        if (last < 0) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = last + 1; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** 无线控制模式：bpp_drawload / epp_qc / unknown，最后证据覆盖前证据（仅标识，不做 ICL/iout 比较）。 */
    private JSONObject parseWirelessMode(String text) throws JSONException {
        String mode = "unknown";
        Integer rxLimit = null;
        String rxTime = "";
        boolean qcEnabled = false;
        for (String line : text.split("\n")) {
            if (line.contains("BPP drawload")) mode = "bpp_drawload";
            if (line.contains("epp plus") || line.contains("EPP+")
                    || line.contains("send_vout_range_request")
                    || line.contains("set adapter voltage")
                    || line.contains("rx_iout_limit")
                    || line.contains("can quick charge!")) {
                mode = "epp_qc";
            }
            Matcher m = RX_IOUT_LIMIT_RE.matcher(line);
            // 函数名 ...op_get_rx_iout_limit:421 里的行号也会匹配，
            // 必须取该行最后一次匹配（真正的 rx_iout_limit: 3800）
            while (m.find()) {
                rxLimit = Integer.parseInt(m.group(1));
                Matcher tm = VOTE_TIME_RE.matcher(line);
                if (tm.find()) rxTime = shiftLogTime(tm.group(1));
            }
            if (line.contains("can quick charge!")) qcEnabled = true;
        }
        return new JSONObject()
                .put("mode", mode)
                .put("rx_iout_limit", rxLimit == null ? JSONObject.NULL : rxLimit)
                .put("rx_iout_limit_time", rxTime)
                .put("qc_enabled", qcEnabled);
    }

    /** 最后一条 wireless power_good_on 的归一化毫秒（会话边界 key，无则 null）。 */
    private Long lastWirelessAttachMs(String text) {
        Long last = null;
        for (String line : text.split("\n")) {
            if (line.contains("power_good_on")) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                if (tm.find()) {
                    last = absLogMs(lastLogFname, shiftLogTime(tm.group(1)));
                }
            }
        }
        return last;
    }

    /** 无线/有线 CP 状态彻底解耦：power_good 只重置无线；usb online/real_type changed 只重置有线；
     *  有线 sc8581_set_operation_mode 行没有 quickchg 上下文前缀，按最近有线边界直接作为有线证据；
     *  无线仍要求 quickchg 上下文，避免旧会话操作模式污染慢充。 */
    private JSONObject parseSessionCpState(String text) throws JSONException {
        Integer wMode = null;
        Integer wWork = null;
        Long wWorkMs = null;
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
        Long dBoundaryAt = null;
        boolean dCtx = false;
        JSONObject dCurMax = null;
        JSONObject dStageCurMax = null;
        JSONObject dQcTarget = null;
        String lastBoundaryKind = null;
        int seq = 0;
        for (String line : text.split("\n")) {
            seq++;
            if (line.contains("power_good_on") || line.contains("power_good_off")) {
                lastBoundaryKind = "wireless";
                wBoundary = true;
                wMode = null;
                wWork = null;
                wWorkMs = null;
                wDecision = null;
                wInputs = null;
                wCtx = false;
                continue;
            }
            if (line.contains("usb online: 0") || line.contains("usb online: 1")
                    || line.contains("real_type changed:")) {
                lastBoundaryKind = "wired";
                dBoundary = true;
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String raw = tm.find() ? tm.group(1) : "";
                dBoundaryAt = raw.isEmpty() ? null
                        : Long.valueOf(absLogMs(lastLogFname, shiftLogTime(raw)));
                dMode = null;
                dModeSeq = -1;
                dRatio = null;
                dCurCp = false;
                dCurCpSeq = -1;
                dBuck = false;
                dCtx = false;
                dCurMax = null;
                dStageCurMax = null;
                dQcTarget = null;
                continue;
            }
            if (line.contains("mca_wireless_quick_charge_")) {
                wCtx = true;
                dCtx = false;
            }
            if (line.contains("mca_quick_charge_") || line.contains("[mca_quick_charge]")
                    || line.contains("strategy_quickchg_")) {
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
                if (wCtx && "wireless".equals(lastBoundaryKind)) wMode = n;
                else if (dCtx && "wired".equals(lastBoundaryKind)) {
                    dMode = n;
                    dModeSeq = seq;
                } else if (line.contains("sc8581_set_operation_mode")
                        && "wired".equals(lastBoundaryKind)) {
                    // 有线 cp_sc8581 行没有 mca_quick_charge_/strategy_quickchg_ 前缀，
                    // 仅依赖 dCtx 会把明确的 mode=1 丢掉，随后被 buckchg 行误判为 Buck。
                    dMode = n;
                    dModeSeq = seq;
                    Matcher opRatio = WIRED_OPERATION_RE.matcher(line);
                    if (opRatio.find()) dRatio = Integer.parseInt(opRatio.group(1));
                }
                continue;
            }
            m = WIRELESS_WORK_MODE_RE.matcher(line);
            if (m.find()) {
                wWork = Integer.parseInt(m.group(1));
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String raw = tm.find() ? tm.group(1) : "";
                wWorkMs = raw.isEmpty() ? null
                        : Long.valueOf(absLogMs(lastLogFname, shiftLogTime(raw)));
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
            Matcher mf = WIRED_FINAL_CUR_MAX_RE.matcher(line);
            if (mf.find() && dCtx) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
                dCurMax = new JSONObject()
                        .put("cur_max", Integer.parseInt(mf.group(1)))
                        .put("secure_cur", Integer.parseInt(mf.group(2)))
                        .put("channel_cur", Integer.parseInt(mf.group(3)))
                        .put("thermal_cur", Integer.parseInt(mf.group(4)))
                        .put("log_time", logTime)
                        .put("at", logEventAt(logTime));
                continue;
            }
            Matcher ms = WIRED_STAGE_CUR_MAX_RE.matcher(line);
            if (ms.find() && dCtx) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
                dStageCurMax = new JSONObject()
                        .put("stage", Integer.parseInt(ms.group(1)))
                        .put("cur_max", Integer.parseInt(ms.group(2)))
                        .put("delta", Integer.parseInt(ms.group(3)))
                        .put("log_time", logTime)
                        .put("at", logEventAt(logTime));
                dCurCp = true;
                dCurCpSeq = seq;
                continue;
            }
            Matcher mq = WIRED_QC_TARGET_RE.matcher(line);
            if (mq.find() && dCtx) {
                Matcher tm = VOTE_TIME_RE.matcher(line);
                String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
                dQcTarget = new JSONObject()
                        .put("fcc", Integer.parseInt(mq.group(1)))
                        .put("ibus", Integer.parseInt(mq.group(2)))
                        .put("source", "mca_qc_get_vbus_change_trend")
                        .put("log_time", logTime)
                        .put("at", logEventAt(logTime));
                dCurCp = true;
                dCurCpSeq = seq;
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
                String logTime = tm.find() ? shiftLogTime(tm.group(1)) : "";
                wInputs = new JSONObject()
                        .put("channel_cur", Integer.parseInt(m.group(1)))
                        .put("temp_max_cur", Integer.parseInt(m.group(2)))
                        .put("tx_adapter_max", Integer.parseInt(m.group(3)))
                        .put("sw_qc_ichg", Integer.parseInt(m.group(4)))
                        .put("sw_thermal_ichg", Integer.parseInt(m.group(5)))
                        .put("log_time", logTime)
                        .put("at", logEventAt(logTime));
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
                .put("w_work_ms", wWorkMs == null ? JSONObject.NULL : wWorkMs)
                .put("w_decision", wDecision == null ? JSONObject.NULL : wDecision)
                .put("w_boundary", wBoundary)
                .put("d_state", dState)
                .put("d_boundary_at", dBoundaryAt == null ? JSONObject.NULL : dBoundaryAt.longValue())
                .put("d_ratio", dRatio == null ? JSONObject.NULL : dRatio)
                .put("d_cur_cp", dCurCp)
                .put("d_buck", dBuck)
                .put("d_cur_max", dCurMax == null ? JSONObject.NULL : dCurMax)
                .put("d_stage_cur_max", dStageCurMax == null ? JSONObject.NULL : dStageCurMax)
                .put("d_qc_target", dQcTarget == null ? JSONObject.NULL : dQcTarget)
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
    private static final Pattern THERMAL_ANY_RE = Pattern.compile(
            "\\[([A-Z0-9\\-]+)\\]\\[VIRTUAL-SENSOR-FORMULA (\\d+)\\]");
    private static final Pattern THERMAL_TARGET_RE = Pattern.compile("\\[wireless_charge (\\d+)\\]");

    private JSONObject parseThermalDump(String text) throws JSONException {
        JSONObject r = new JSONObject()
                .put("scene", JSONObject.NULL).put("virtual_temp", JSONObject.NULL)
                .put("target", JSONObject.NULL);
        for (String line : text.split("\n")) {
            Matcher m = THERMAL_WLS_RE.matcher(line);
            if (m.find()) {
                r.put("scene", THERMAL_SCENES.getOrDefault(m.group(1), m.group(1)));
                r.put("virtual_temp", Integer.parseInt(m.group(2)) / 1000.0);
                Matcher t = THERMAL_TARGET_RE.matcher(line);
                r.put("target", t.find() ? Integer.parseInt(t.group(1)) : JSONObject.NULL);
                continue;
            }
            Matcher any = THERMAL_ANY_RE.matcher(line);
            if (any.find()) {
                r.put("scene", thermalSceneForSegment(any.group(1)));
                r.put("virtual_temp", Integer.parseInt(any.group(2)) / 1000.0);
                r.put("target", JSONObject.NULL);
            }
        }
        return r;
    }

    private static String thermalSceneForSegment(String segment) {
        final String suffix = "-MONITOR-WIRELESS";
        for (String key : THERMAL_SCENES.keySet()) {
            if (!key.endsWith(suffix)) continue;
            String prefix = key.substring(0, key.length() - suffix.length());
            if (!prefix.isEmpty()
                    && (segment.equals(prefix) || segment.startsWith(prefix + "-"))) {
                return THERMAL_SCENES.get(key);
            }
        }
        return THERMAL_SCENES.get("MONITOR-WIRELESS");
    }
}
