package com.leowood.chargingdashboard;

/** Resolves the physical input before any shared CP telemetry is interpreted. */
final class InputSourceResolver {
    private InputSourceResolver() {}

    static String resolve(Boolean usbOnline, double usbVbusMv, boolean wirelessSignal) {
        if (Boolean.TRUE.equals(usbOnline)) {
            return "wired";
        }
        if (Boolean.FALSE.equals(usbOnline)) {
            return wirelessSignal ? "wireless" : "none";
        }
        if (wirelessSignal) {
            return "wireless";
        }
        return Double.isFinite(usbVbusMv) && usbVbusMv > 1000.0
                ? "wired" : "none";
    }

    static String cpIbusOwner(String inputSource, double cpIbusMa) {
        if (!Double.isFinite(cpIbusMa)) {
            return "none";
        }
        return "wired".equals(inputSource) || "wireless".equals(inputSource)
                ? inputSource : "none";
    }

    static boolean resolveWirelessConnected(Boolean latched,
                                            String inputSource,
                                            double voutMv) {
        if (latched != null) {
            return latched;
        }
        return "wireless".equals(inputSource)
                || Double.isFinite(voutMv) && voutMv > 1000.0;
    }

    static String resolveWiredInputSource(String wiredState,
                                          double cpIbusMa,
                                          double usbIbusMa,
                                          boolean usbOnline,
                                          Boolean chargingEnabled,
                                          double batteryCurrentMa) {
        boolean cpValid = Double.isFinite(cpIbusMa);
        boolean stopped = Boolean.FALSE.equals(chargingEnabled)
                && Double.isFinite(batteryCurrentMa)
                && Math.abs(batteryCurrentMa) <= 300.0;
        boolean cpIdleWhileStopped = stopped && cpValid && Math.abs(cpIbusMa) <= 50.0;
        if ("cp".equals(wiredState) && cpValid && !cpIdleWhileStopped) {
            return "cp_ibus_total";
        }
        if (usbOnline && Double.isFinite(usbIbusMa)) {
            return "usb_uevent";
        }
        return "cp".equals(wiredState) && cpValid ? "cp_ibus_total" : null;
    }
}
