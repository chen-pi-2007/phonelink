package com.phonelink.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

/** 主手机页面：界面是网页（assets/ui/source.html），授权、更新等逻辑在这里。 */
public class SourceActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Updater.Info pending;
    private java.io.File downloaded;
    private volatile boolean busy;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "PLNative");
        web.loadUrl("file:///android_asset/ui/source.html");
        setContentView(web);
        applyHideRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // 开关开着但服务没跑（更新后常见）：自动“关掉再打开”
        if (listenerEnabled() && "未启动".equals(BridgeListenerService.status)) UpdatedReceiver.rebindListener(this);
        long lastCheck = Proto.prefs(this).getLong("last_update_check", 0);
        if (System.currentTimeMillis() - lastCheck > 6 * 3600_000L || getIntent().getBooleanExtra("update", false)) {
            checkUpdate(false);
        }
    }

    /** 返回键：先关掉设置页，再退出 */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("PLS.back()", v -> { if (!"true".equals(v)) SourceActivity.super.onBackPressed(); });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause(); // 不在前台时网页停止运行，省电
    }

    /** 网页可调用的方法 */
    private class Bridge {
        @JavascriptInterface public String status() {
            try {
                JSONObject o = new JSONObject();
                o.put("listener", listenerEnabled());
                o.put("svc", BridgeListenerService.status);
                o.put("bt", Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT));
                o.put("battery", getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
                o.put("beat", granted(Manifest.permission.RECORD_AUDIO));
                o.put("hide", Proto.prefs(SourceActivity.this).getBoolean(Proto.KEY_HIDE_RECENTS, false));
                o.put("ver", Updater.myVersionName(SourceActivity.this));
                o.put("android", Build.VERSION.RELEASE);
                o.put("model", Build.MODEL);
                o.put("display", BridgeListenerService.displayName);
                o.put("nowTitle", BridgeListenerService.nowTitle);
                o.put("nowArtist", BridgeListenerService.nowArtist);
                o.put("nowApp", BridgeListenerService.nowApp);
                o.put("nowArt", BridgeListenerService.nowArt);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        @JavascriptInterface public void openListener() { ui.post(() -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))); }
        @JavascriptInterface public void reqBt() {
            ui.post(() -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS}, 1);
                else if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
                else toast("这台手机不需要单独授权蓝牙");
            });
        }
        @JavascriptInterface public void battery() { ui.post(SourceActivity.this::askIgnoreBattery); }
        @JavascriptInterface public void appDetails() {
            ui.post(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        }
        @JavascriptInterface public void reqBeat() { ui.post(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 6)); }
        @JavascriptInterface public void setHide(boolean on) {
            Proto.prefs(SourceActivity.this).edit().putBoolean(Proto.KEY_HIDE_RECENTS, on).apply();
            ui.post(SourceActivity.this::applyHideRecents);
        }
        @JavascriptInterface public void beatTest() { ui.post(() -> startActivity(new Intent(SourceActivity.this, BeatTestActivity.class))); }
        @JavascriptInterface public void copyNavLog() { ui.post(SourceActivity.this::copyNavLogNow); }
        @JavascriptInterface public void switchMode() {
            ui.post(() -> {
                Proto.prefs(SourceActivity.this).edit().remove(Proto.KEY_MODE).apply();
                startActivity(new Intent(SourceActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            });
        }
        @JavascriptInterface public void updateClick() { ui.post(SourceActivity.this::onUpdateClick); }
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void toast(String t) {
        web.evaluateJavascript("PLS.toast(" + JSONObject.quote(t) + ")", null);
    }

    private void setUpdate(String title, String sub, boolean isNew) {
        web.evaluateJavascript("PLS.onUpdate(" + JSONObject.quote(title) + "," + JSONObject.quote(sub) + "," + isNew + ")", null);
    }

    // ---------------- 更新 ----------------

    private void onUpdateClick() {
        if (busy) return;
        if (downloaded != null) { install(downloaded); return; }
        if (pending != null) { downloadUpdate(); return; }
        checkUpdate(true);
    }

    private void checkUpdate(boolean manual) {
        busy = true;
        if (manual) setUpdate("正在检查…", "", false);
        new Thread(() -> {
            Updater.Info info = Updater.fetchLatest();
            Proto.prefs(this).edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            ui.post(() -> {
                busy = false;
                String mine = Updater.myVersionName(this);
                if (info == null) {
                    if (manual) setUpdate("检查失败，点击重试", "", false);
                } else if (info.versionCode > Updater.myVersion(this)) {
                    pending = info;
                    setUpdate("有新版本，点击更新", "v" + info.versionName, true);
                } else {
                    setUpdate("已是最新版", "v" + mine, false);
                }
            });
        }).start();
    }

    private void downloadUpdate() {
        busy = true;
        setUpdate("正在下载…", "v" + pending.versionName, true);
        new Thread(() -> {
            try {
                java.io.File f = Updater.download(this, pending, m -> {});
                ui.post(() -> { busy = false; downloaded = f; install(f); });
            } catch (Exception e) {
                ui.post(() -> { busy = false; setUpdate("下载失败，点击重试", "", false); });
            }
        }).start();
    }

    /** 优先用自我更新会话（安卓 12+ 之后可免确认）；不行再弹系统安装界面 */
    private void install(java.io.File f) {
        setUpdate("正在安装…", "v" + pending.versionName, true);
        new Thread(() -> {
            try {
                Updater.installSession(this, f);
            } catch (Exception e) {
                ui.post(() -> Updater.promptInstall(this, f));
            }
        }).start();
    }

    // ---------------- 其他 ----------------

    private void askIgnoreBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) { toast("已经忽略电池优化了"); return; }
        @SuppressLint("BatteryLife")
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void applyHideRecents() {
        boolean on = Proto.prefs(this).getBoolean(Proto.KEY_HIDE_RECENTS, false);
        ActivityManager am = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask t : am.getAppTasks()) t.setExcludeFromRecents(on);
    }

    private void copyNavLogNow() {
        try {
            java.io.File f = NavBridge.logFile(this);
            String txt = f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8") : "";
            if (txt.length() > 60000) txt = txt.substring(txt.length() - 60000);
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("nav_log", txt));
            toast(txt.isEmpty() ? "还没有记录：连着副屏导航一段再来" : "已复制 " + txt.length() + " 字");
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }
}
