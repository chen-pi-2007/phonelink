package com.phonelink.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;

/** 主手机设置页：授权、选择副屏、隐藏后台。 */
public class SourceActivity extends Activity {
    private TextView statusView;
    private Button updateBtn;
    private Updater.Info pending;
    private java.io.File downloaded;
    private boolean busy;
    private LinearLayout deviceList;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("主手机 · 音源端");
        title.setTextSize(24);
        root.addView(title);

        updateBtn = btn("检查更新（当前 v" + Updater.myVersionName(this) + "）", this::onUpdateClick);
        root.addView(updateBtn);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 20, 0, 20);
        root.addView(statusView);

        root.addView(btn("① 开启通知使用权（读取播放信息）", () ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        root.addView(btn("② 授予蓝牙权限", () -> {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            refreshDevices();
        }));

        root.addView(btn("③ 忽略电池优化（防止被杀）", this::askIgnoreBattery));

        root.addView(btn("④ 打开 vivo 后台耗电管理（手动设为允许）", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        root.addView(btn("复制导航诊断记录（高德/百度/腾讯）", () -> {
            try {
                java.io.File f = NavBridge.logFile(this);
                String txt = f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8") : "";
                if (txt.length() > 60000) txt = txt.substring(txt.length() - 60000);
                android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("nav_log", txt));
                android.widget.Toast.makeText(this, txt.isEmpty() ? "还没有记录，先用地图导航一段" : "已复制 " + txt.length() + " 字",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "复制失败：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        }));

        CheckBox hide = new CheckBox(this);
        hide.setText("从最近任务中隐藏（类似 GKD）");
        hide.setTextSize(16);
        hide.setChecked(Proto.prefs(this).getBoolean(Proto.KEY_HIDE_RECENTS, false));
        hide.setOnCheckedChangeListener((v, on) -> {
            Proto.prefs(this).edit().putBoolean(Proto.KEY_HIDE_RECENTS, on).apply();
            applyHideRecents();
        });
        root.addView(hide);

        TextView pick = new TextView(this);
        pick.setText("⑤ 在系统蓝牙里和副屏配对一次即可。由副屏来连接本机：副屏 App 打开时才连，平时本机只在后台待机，不耗电。");
        pick.setTextSize(16);
        pick.setPadding(0, 30, 0, 10);
        root.addView(pick);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);

        root.addView(btn("切换为副屏模式", () -> {
            Proto.prefs(this).edit().remove(Proto.KEY_MODE).apply();
            startActivity(new Intent(this, MainActivity.class).putExtra("choose", true));
            finish();
        }));

        setContentView(sv);
        applyHideRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
        // 若服务被系统解绑过，请求重新绑定
        if (listenerEnabled()) {
            NotificationListenerService.requestRebind(new ComponentName(this, BridgeListenerService.class));
        }
        tick.run();
        long lastCheck = Proto.prefs(this).getLong("last_update_check", 0);
        if (System.currentTimeMillis() - lastCheck > 6 * 3600_000L || getIntent().getBooleanExtra("update", false)) {
            checkUpdate(false);
        }
    }

    // ---------------- 更新 ----------------

    private void onUpdateClick() {
        if (busy) return;
        if (downloaded != null) { Updater.promptInstall(this, downloaded); return; }
        if (pending != null) { downloadUpdate(); return; }
        checkUpdate(true);
    }

    private void checkUpdate(boolean manual) {
        busy = true;
        if (manual) updateBtn.setText("正在检查更新…");
        new Thread(() -> {
            Updater.Info info = Updater.fetchLatest();
            Proto.prefs(this).edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            ui.post(() -> {
                busy = false;
                int mine = Updater.myVersion(this);
                if (info == null) {
                    updateBtn.setText(manual ? "检查失败（网络不通），点此重试" : "检查更新（当前 v" + Updater.myVersionName(this) + "）");
                } else if (info.versionCode > mine) {
                    pending = info;
                    updateBtn.setText("★ 有新版 v" + info.versionName + "，点此更新"
                            + (info.notes.isEmpty() ? "" : "\n" + info.notes));
                } else {
                    updateBtn.setText("已是最新版 v" + Updater.myVersionName(this));
                }
            });
        }).start();
    }

    private void downloadUpdate() {
        busy = true;
        new Thread(() -> {
            try {
                java.io.File f = Updater.download(this, pending, m -> ui.post(() -> updateBtn.setText(m)));
                ui.post(() -> {
                    busy = false;
                    downloaded = f;
                    updateBtn.setText("下载完成，点此安装 v" + pending.versionName);
                    Updater.promptInstall(this, f);
                });
            } catch (Exception e) {
                ui.post(() -> { busy = false; updateBtn.setText("下载失败：" + e.getMessage() + "，点此重试"); });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            statusView.setText("通知使用权：" + (listenerEnabled() ? "已开启" : "未开启")
                    + "\n连接状态：" + BridgeListenerService.status);
            ui.postDelayed(this, 1000);
        }
    };

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    @SuppressLint("MissingPermission")
    private void refreshDevices() {
        deviceList.removeAllViews();
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            TextView t = new TextView(this);
            t.setText("（先完成第 ② 步）");
            deviceList.addView(t);
            return;
        }
        BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
        if (ad == null) return;
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        String cur = Proto.prefs(this).getString(Proto.KEY_TARGET, "");
        for (BluetoothDevice d : bonded) {
            String label = (d.getAddress().equals(cur) ? "✔ " : "") + d.getName() + "  " + d.getAddress();
            deviceList.addView(btn(label, () -> {
                Proto.prefs(this).edit().putString(Proto.KEY_TARGET, d.getAddress()).apply();
                refreshDevices();
            }));
        }
        if (bonded.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("没有已配对设备");
            deviceList.addView(t);
        }
    }

    private void askIgnoreBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        @SuppressLint("BatteryLife")
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void applyHideRecents() {
        boolean on = Proto.prefs(this).getBoolean(Proto.KEY_HIDE_RECENTS, false);
        ActivityManager am = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask t : am.getAppTasks()) t.setExcludeFromRecents(on);
    }

    private Button btn(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        return b;
    }
}
