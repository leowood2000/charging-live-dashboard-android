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
}
