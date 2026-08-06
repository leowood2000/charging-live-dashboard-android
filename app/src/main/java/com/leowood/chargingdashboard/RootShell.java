package com.leowood.chargingdashboard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 通过 su 执行 root 命令（KernelSU / Magisk）。 */
public final class RootShell {
    private static final String[] SU_CANDIDATES = {
        // 优先 PATH 中的 su（KernelSU/Magisk 都挂到 /system/bin/su）
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/busybox",
    };
    private static volatile String suPath = null;
    private static volatile long suCheckedAt = 0;

    private RootShell() {}

    public static String exec(String command, long timeoutSec) {
        String su = resolveSu();
        if (su == null) return "";
        try {
            Process p = new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isRootAvailable() {
        String out = exec("id", 5);
        return out.contains("uid=0");
    }

    private static String resolveSu() {
        if (suPath != null) return suPath;
        // 短探测：每个候选最多 2 秒，避免启动时长时间黑屏
        for (String cand : SU_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(cand, "-c", "id").redirectErrorStream(true).start();
                if (p.waitFor(2, TimeUnit.SECONDS)) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        if (sb.toString().contains("uid=0")) {
                            suPath = cand;
                            return cand;
                        }
                    }
                } else {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }
        suCheckedAt = System.currentTimeMillis();
        return null;
    }
}
