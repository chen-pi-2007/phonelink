package com.phonelink.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 副屏：蓝牙服务端 + 全屏显示与控制。 */
public class DisplayActivity extends Activity {
    private static final int FG = Color.WHITE;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF4FC3F7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, titleView, artistView, extraView, timeView, rawView;
    private ImageView artView;
    private SeekBar seek;
    private Button playBtn, queueBtn;
    private ScrollView queuePanel;
    private LinearLayout sourceRow;
    private String sourceSig;
    private LinearLayout queueList;

    private volatile boolean running = true;
    private volatile OutputStream out;
    private volatile BluetoothSocket client;
    private BluetoothServerSocket server;

    // 进度插值
    private long basePos, baseAt, duration;
    private float speed = 1f;
    private boolean playing, userSeeking;
    private JSONObject last;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        buildUi();
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            startServer();
        }
        ui.post(progressTick);
        ui.postDelayed(this::onlineUpdateCheck, 30_000L);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        startServer();
    }

    @Override
    protected void onDestroy() {
        running = false;
        closeQuietly();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        frame.addView(root);

        statusView = text(14, DIM);
        statusView.setOnLongClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("choose", true));
            Proto.prefs(this).edit().remove(Proto.KEY_MODE).apply();
            finish();
            return true;
        });
        root.addView(statusView);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        sourceRow = new LinearLayout(this);
        hs.addView(sourceRow);
        root.addView(hs);

        artView = new ImageView(this);
        artView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(artView, new LinearLayout.LayoutParams(-1, 0, 1f));

        titleView = text(30, FG);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(3);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setOnLongClickListener(v -> {
            rawView.setVisibility(rawView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        });
        root.addView(titleView);

        artistView = text(20, DIM);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        root.addView(artistView);

        extraView = text(22, ACCENT);
        extraView.setGravity(Gravity.CENTER);
        extraView.setMaxLines(2);
        root.addView(extraView);

        rawView = text(12, DIM);
        rawView.setVisibility(View.GONE);
        root.addView(rawView);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setPadding(dp(8), dp(16), dp(8), dp(4));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {}
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (duration > 0) sendCmd("seekTo", duration * s.getProgress() / 1000);
            }
        });
        root.addView(seek);

        timeView = text(16, DIM);
        timeView.setGravity(Gravity.CENTER);
        root.addView(timeView);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(ctrl("−15s", () -> sendCmd("seekBy", -15000)), weight());
        row.addView(ctrl("⏮", () -> sendCmd("prev", 0)), weight());
        playBtn = ctrl("▶", () -> sendCmd("toggle", 0));
        row.addView(playBtn, weight());
        row.addView(ctrl("⏭", () -> sendCmd("next", 0)), weight());
        row.addView(ctrl("+15s", () -> sendCmd("seekBy", 15000)), weight());
        root.addView(row);

        queueBtn = ctrl("播放列表", () ->
                queuePanel.setVisibility(queuePanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        queueBtn.setVisibility(View.GONE);
        root.addView(queueBtn, new LinearLayout.LayoutParams(-1, dp(64)));

        queuePanel = new ScrollView(this);
        queuePanel.setBackgroundColor(0xF0101418);
        queuePanel.setVisibility(View.GONE);
        queueList = new LinearLayout(this);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding(dp(16), dp(24), dp(16), dp(24));
        queuePanel.addView(queueList);
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(-1, -1);
        qp.bottomMargin = dp(80);
        frame.addView(queuePanel, qp);

        setContentView(frame);
        statusView.setText("等待主手机连接…（长按此处切换模式）  v" + Updater.myVersionName(this));
        titleView.setText("未连接");
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private Button ctrl(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(24);
        b.setAllCaps(false);
        b.setTextColor(FG);
        b.setBackgroundColor(0xFF1E2329);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void render(JSONObject o) {
        last = o;
        renderSources(o);
        if (o.optBoolean("none")) {
            titleView.setText("主手机没有在播放");
            artistView.setText("");
            extraView.setText("");
            return;
        }
        statusView.setText("已连接 · " + o.optString("app"));
        String title = o.optString("title");
        String artist = o.optString("artist");
        String album = o.optString("album");
        titleView.setText(title.isEmpty() ? o.optString("dTitle") : title);
        artistView.setText(artist + (album.isEmpty() ? "" : "  ·  " + album));
        // 有的 App 把歌词放在副标题/描述字段，单独显示出来
        String sub = o.optString("dSub");
        String desc = o.optString("dDesc");
        String extra = !sub.isEmpty() && !sub.equals(artist) ? sub : (!desc.isEmpty() && !desc.equals(album) ? desc : "");
        extraView.setText(extra);

        rawView.setText("pkg=" + o.optString("pkg") + "\ntitle=" + title + "\nartist=" + artist
                + "\nalbum=" + album + "\ndTitle=" + o.optString("dTitle") + "\ndSub=" + sub
                + "\ndDesc=" + desc + "\nactions=" + o.optLong("actions"));

        if (o.has("art")) {
            byte[] bytes = Base64.decode(o.optString("art"), Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            artView.setImageBitmap(bmp);
        } else if (o.has("hasArt") && !o.optBoolean("hasArt")) {
            artView.setImageDrawable(null);
        }

        duration = o.optLong("duration");
        basePos = o.optLong("pos");
        baseAt = SystemClock.elapsedRealtime();
        speed = (float) o.optDouble("speed", 1.0);
        playing = o.optBoolean("playing");
        playBtn.setText(playing ? "⏸" : "▶");

        JSONArray q = o.optJSONArray("queue");
        if (q == null || q.length() == 0) {
            queueBtn.setVisibility(View.GONE);
            queuePanel.setVisibility(View.GONE);
        } else {
            queueBtn.setVisibility(View.VISIBLE);
            queueBtn.setText("播放列表（" + q.length() + "）");
            renderQueue(q);
        }
    }

    /** 来源切换：自动 / 固定某个 App。▶ 表示该 App 正在播放。 */
    private void renderSources(JSONObject o) {
        JSONArray ss = o.optJSONArray("sessions");
        String pinned = o.optString("pinned");
        String cur = o.optString("pkg");
        String sig = (ss == null ? "" : ss.toString()) + "|" + pinned + "|" + cur;
        if (sig.equals(sourceSig)) return;
        sourceSig = sig;
        sourceRow.removeAllViews();
        if (ss == null || ss.length() < 2) return;
        sourceRow.addView(chip("自动", pinned.isEmpty(), () -> sendPin("")));
        for (int i = 0; i < ss.length(); i++) {
            JSONObject it = ss.optJSONObject(i);
            String pkg = it.optString("pkg");
            String label = (it.optInt("state") == 3 ? "▶ " : "") + it.optString("app");
            sourceRow.addView(chip(label, pkg.equals(cur), () -> sendPin(pkg)));
        }
    }

    private TextView chip(String label, boolean on, Runnable r) {
        TextView t = text(18, on ? Color.BLACK : FG);
        t.setText(label);
        t.setBackgroundColor(on ? ACCENT : 0xFF1E2329);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(6), dp(8), dp(6));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void sendPin(String pkg) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "cmd");
            o.put("c", "pin");
            o.put("p", pkg);
            sendJson(o);
        } catch (Exception ignored) {}
    }

    private String queueSig;

    private void renderQueue(JSONArray q) {
        String sig = q.toString();
        if (sig.equals(queueSig)) return;
        queueSig = sig;
        queueList.removeAllViews();
        for (int i = 0; i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            TextView t = text(20, it.optBoolean("active") ? ACCENT : FG);
            t.setPadding(0, dp(14), 0, dp(14));
            String sub = it.optString("sub");
            t.setText(it.optString("title") + (sub.isEmpty() ? "" : "  —  " + sub));
            long id = it.optLong("id");
            t.setOnClickListener(v -> { sendCmd("queue", id); queuePanel.setVisibility(View.GONE); });
            queueList.addView(t);
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            long pos = basePos;
            if (playing) pos += (long) ((SystemClock.elapsedRealtime() - baseAt) * speed);
            if (duration > 0) {
                pos = Math.min(pos, duration);
                if (!userSeeking) seek.setProgress((int) (pos * 1000 / duration));
                timeView.setText(fmt(pos) + " / " + fmt(duration));
            } else {
                timeView.setText(last == null ? "" : fmt(pos));
            }
            updateKeepScreenOn();
            ui.postDelayed(this, 1000);
        }
    };

    // 省电：连着主手机且在播放（或暂停不到 2 分钟）才常亮，否则交给系统按时灭屏
    private volatile boolean connected;
    private long lastPlayingAt;
    private boolean keepOn;

    private void updateKeepScreenOn() {
        long now = SystemClock.elapsedRealtime();
        if (connected && playing) lastPlayingAt = now;
        boolean want = connected && (playing || now - lastPlayingAt < 120_000);
        if (want == keepOn) return;
        keepOn = want;
        if (want) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            wakeScreen();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** 主手机开始播放时把已经灭掉的屏幕点亮。 */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm.isInteractive()) return;
        android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "phonelink:wake");
        wl.acquire(3000);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------- 蓝牙服务端 ----------------

    private boolean serverStarted;

    @SuppressLint("MissingPermission")
    private void startServer() {
        if (serverStarted) return;
        serverStarted = true;
        new Thread(() -> {
            BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
            if (ad == null) { setStatus("本机没有蓝牙"); return; }
            while (running) {
                try {
                    if (!ad.isEnabled()) {
                        setStatus("蓝牙未开启，尝试开启…");
                        try { Runtime.getRuntime().exec(new String[]{"su", "-c", "svc bluetooth enable"}).waitFor(); }
                        catch (Exception ignored) {}
                        Thread.sleep(3000);
                        continue;
                    }
                    if (server == null) server = ad.listenUsingRfcommWithServiceRecord(Proto.SDP_NAME, Proto.UUID_SPP);
                    setStatus("等待主手机连接…  本机：" + ad.getName() + "（长按此处切换模式）");
                    BluetoothSocket s = server.accept();
                    closeQuietly();
                    client = s;
                    out = s.getOutputStream();
                    String name = s.getRemoteDevice().getName();
                    setStatus("已连接 " + name);
                    connected = true;
                    JSONObject hello = new JSONObject();
                    hello.put("t", "hello");
                    hello.put("ver", Updater.myVersion(this));
                    sendJson(hello);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        try {
                            JSONObject o = new JSONObject(line);
                            String t = o.optString("t");
                            if ("state".equals(t)) ui.post(() -> render(o));
                            else onUpdatePacket(t, o);
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    setStatus("连接断开：" + e.getMessage());
                    try { if (server != null) server.close(); } catch (Exception ignored) {}
                    server = null;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
                closeQuietly();
                connected = false;
                ui.post(() -> titleView.setText("主手机已断开，等待重连…"));
            }
        }, "bt-server").start();
    }

    // ---------------- 更新：蓝牙接收主手机推来的新版 / 自己联网检查 ----------------

    private java.io.FileOutputStream apkOut;
    private long apkSize, apkGot;
    private String apkSha;

    private void onUpdatePacket(String t, JSONObject o) {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "update.apk");
            if ("apk".equals(t)) {
                if (apkOut != null) apkOut.close();
                apkOut = new java.io.FileOutputStream(f);
                apkSize = o.optLong("size");
                apkGot = 0;
                apkSha = o.optString("sha256");
            } else if ("chunk".equals(t) && apkOut != null) {
                byte[] d = Base64.decode(o.optString("d"), Base64.DEFAULT);
                apkOut.write(d);
                apkGot += d.length;
                setStatus("正在从主手机接收新版本 " + (apkGot * 100 / Math.max(1, apkSize)) + "%");
            } else if ("apkEnd".equals(t) && apkOut != null) {
                apkOut.close();
                apkOut = null;
                if (Updater.sha256(f).equals(apkSha)) {
                    setStatus("新版本接收完成，正在安装，稍后自动重启…");
                    Updater.installRoot(this, f);
                } else {
                    setStatus("新版本校验失败，已丢弃");
                }
            }
        } catch (Exception e) {
            setStatus("接收更新失败：" + e.getMessage());
        }
    }

    private void onlineUpdateCheck() {
        new Thread(() -> {
            Updater.Info info = Updater.fetchLatest();
            if (info == null || info.versionCode <= Updater.myVersion(this)) return;
            try {
                java.io.File f = Updater.download(this, info, m -> {});
                setStatus("已下载新版 v" + info.versionName + "，正在安装，稍后自动重启…");
                Updater.installRoot(this, f);
            } catch (Exception ignored) {}
        }, "update-check").start();
        ui.postDelayed(this::onlineUpdateCheck, 6 * 3600_000L);
    }

    private void setStatus(String s) { ui.post(() -> statusView.setText(s)); }

    private void sendCmd(String c, long v) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "cmd");
            o.put("c", c);
            o.put("v", v);
            sendJson(o);
        } catch (Exception ignored) {}
    }

    private void sendJson(JSONObject o) {
        OutputStream os = out;
        if (os == null) return;
        new Thread(() -> {
            try {
                synchronized (DisplayActivity.this) {
                    os.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void closeQuietly() {
        out = null;
        BluetoothSocket c = client;
        client = null;
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
