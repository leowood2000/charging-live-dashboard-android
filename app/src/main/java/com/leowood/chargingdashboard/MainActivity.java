package com.leowood.chargingdashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private WebView webView;
    private final SnapshotCollector collector = new SnapshotCollector();
    private final ScheduledExecutorService fastScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService slowScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** App 在前台时才采集，后台/锁屏停止 root 读写，避免耗电。 */
    private volatile boolean active = true;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B1220);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        // 快速数据：sysfs/battery/thermal 每 3 秒（固定延迟，避免积压）
        fastScheduler.scheduleWithFixedDelay(() -> {
            if (!active) return;
            collector.collectFast();
            ui.post(() -> {
                if (webView != null) webView.evaluateJavascript(
                        "window.__onSnapshot && window.__onSnapshot();", null);
            });
        }, 0, 3, TimeUnit.SECONDS);
        // 慢速日志：投票/会话/EPP 每 20 秒
        slowScheduler.scheduleWithFixedDelay(() -> {
            if (!active) return;
            collector.collectLogs();
            // 日志完成后立即通知页面刷新，不等下一轮快速采集
            ui.post(() -> {
                if (webView != null) webView.evaluateJavascript(
                        "window.__onSnapshot && window.__onSnapshot();", null);
            });
        }, 2, 20, TimeUnit.SECONDS);
    }

    @Override
    protected void onPause() {
        active = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        fastScheduler.shutdownNow();
        slowScheduler.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface
        public String getSnapshotJson() {
            return collector.getSnapshotJson();
        }
    }
}
