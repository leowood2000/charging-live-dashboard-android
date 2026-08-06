package com.leowood.chargingdashboard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    };
    private static volatile String suPath = null;
    private static volatile long suCheckedAt = 0;
    private static final long SU_RETRY_MS = 8000;
    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool();

    private RootShell() {}

    /** 边运行边消费子进程输出，避免管道缓冲区写满导致 waitFor 超时。 */
    public static String exec(String command, long timeoutSec) {
        String su = resolveSu();
        if (su == null) return "";
        Process p = null;
        try {
            p = new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start();
            Process proc = p;
            Future<String> outFuture = IO_POOL.submit(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                return sb.toString();
            });
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                outFuture.cancel(true);
                return "";
            }
            return outFuture.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return "";
        }
    }

    public static boolean isRootAvailable() {
        String out = exec("id", 5);
        return out.contains("uid=0");
    }

    private static String resolveSu() {
        if (suPath != null) return suPath;
        // 失败后至少间隔 8 秒再重试，避免每 3 秒全量探测
        if (System.currentTimeMillis() - suCheckedAt < SU_RETRY_MS) return null;
        // 短探测：每个候选最多 2 秒，避免启动时长时间黑屏
        for (String cand : SU_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(cand, "-c", "id").redirectErrorStream(true).start();
                Process proc = p;
                Future<String> outFuture = IO_POOL.submit(() -> {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    }
                    return sb.toString();
                });
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    outFuture.cancel(true);
                    continue;
                }
                if (outFuture.get(1, TimeUnit.SECONDS).contains("uid=0")) {
                    suPath = cand;
                    return cand;
                }
            } catch (Exception ignored) {
            }
        }
        suCheckedAt = System.currentTimeMillis();
        return null;
    }
}
