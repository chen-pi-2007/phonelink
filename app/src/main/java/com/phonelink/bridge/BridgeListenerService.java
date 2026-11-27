package com.phonelink.bridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主手机端核心。
 * 作为通知监听服务由系统绑定：进程被杀后系统会自动重新绑定，这是保活的关键（同 GKD 的无障碍服务思路）。
 * 职责：读取当前媒体会话 -> 通过蓝牙 RFCOMM 推给副屏；接收副屏的控制命令 -> 执行。
 */
public class BridgeListenerService extends NotificationListenerService {
    private static final String TAG = "PhoneLinkSrc";
    private static final String CH = "bridge";

    static volatile String status = "未启动";

    private HandlerThread worker;
    private Handler h;
    private MediaSessionManager msm;
    private MediaController current;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private String lastArtKey;
    private String lastArtB64;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> h.post(this::pickController);

    /** 所有活跃会话都挂监听，任何一个 App 开始播放都能立刻感知（修复：只盯着第一个会话导致切不到 QQ 音乐）。 */
    private final Map<MediaSession.Token, MediaController> watched = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    /** 每个会话最近一次进入“播放中”的时间，多个同时播放时选最新开始的。 */
    private final Map<MediaSession.Token, Long> playingSince = new HashMap<>();
    /** 副屏手动固定的来源包名，null 为自动。 */
    private String pinnedPkg;

    private MediaController.Callback callbackFor(MediaSession.Token token) {
        return new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { if (isCurrent(token)) pushState(); }
            @Override public void onPlaybackStateChanged(PlaybackState s) {
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    if (!playingSince.containsKey(token)) {
                        playingSince.put(token, SystemClock.elapsedRealtime());
                    }
                } else {
                    playingSince.remove(token);
                }
                pickController();
                if (isCurrent(token)) pushState();
            }
            @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { if (isCurrent(token)) pushState(); }
            @Override public void onSessionDestroyed() { pickController(); }
        };
    }

    private boolean isCurrent(MediaSession.Token t) {
        return current != null && current.getSessionToken().equals(t);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (running) return;
        status = "服务已启动，正在初始化…";
        try {
            init();
        } catch (Throwable e) {
            // 把启动失败的原因直接显示在设置页，方便排查
            status = "启动出错：" + e;
            Log.e(TAG, "init failed", e);
        }
    }

    private void init() {
        running = true;
        startFg();
        worker = new HandlerThread("bridge");
        worker.start();
        h = new Handler(worker.getLooper());
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, BridgeListenerService.class);
        h.post(() -> {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, me, h);
            } catch (SecurityException e) {
                status = "缺少通知使用权";
            }
            pickController();
        });
        nav = new NavBridge(this, o -> h.post(() -> send(o)));
        nav.start();
        new Thread(this::serverLoop, "bt-server").start();
        scheduleUpdateCheck(60_000L);
        // 兜底轮询（省电）：连着副屏时每 5 秒校准一次进度，没连时每 30 秒查一下播放器；平时全靠事件回调
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) return;
                pickController();
                if (out != null) pushState();
                h.postDelayed(this, out != null ? 5000 : 60000);
            }
        }, 5000);
    }

    private NavBridge nav;

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        if (nav != null) nav.onPosted(sbn);
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        if (nav != null) nav.onRemoved(sbn);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // 被系统解绑时请求重新绑定
        requestRebind(new ComponentName(this, BridgeListenerService.class));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (nav != null) nav.stop();
        closeSocket();
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        for (Map.Entry<MediaSession.Token, MediaController> e : watched.entrySet()) {
            e.getValue().unregisterCallback(callbacks.get(e.getKey()));
        }
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "副屏桥接", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SourceActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("手机副屏桥接运行中")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
    }

    // ---------------- 媒体会话 ----------------

    private List<MediaController> sessions = new ArrayList<>();

    private void pickController() {
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(this, BridgeListenerService.class));
        } catch (SecurityException e) {
            status = "缺少通知使用权";
            return;
        }
        sessions = list;
        // 同步监听集合
        Set<MediaSession.Token> alive = new HashSet<>();
        for (MediaController c : list) {
            MediaSession.Token t = c.getSessionToken();
            alive.add(t);
            if (!watched.containsKey(t)) {
                MediaController.Callback cb = callbackFor(t);
                c.registerCallback(cb, h);
                watched.put(t, c);
                callbacks.put(t, cb);
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) playingSince.put(t, 0L);
            }
        }
        for (Iterator<Map.Entry<MediaSession.Token, MediaController>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MediaSession.Token, MediaController> e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().unregisterCallback(callbacks.remove(e.getKey()));
                playingSince.remove(e.getKey());
                it.remove();
            }
        }


        MediaController best = null;
        // 1) 副屏手动固定的来源
        if (pinnedPkg != null) {
            for (MediaController c : list) if (c.getPackageName().equals(pinnedPkg)) { best = c; break; }
        }
        // 2) 正在播放的，取最近开始播放的那个
        if (best == null) {
            long bestAt = -1;
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s == null || s.getState() != PlaybackState.STATE_PLAYING) continue;
                Long at = playingSince.get(c.getSessionToken());
                long v = at == null ? 0 : at;
                if (v > bestAt) { bestAt = v; best = c; }
            }
        }
        // 3) 都没在播：保持当前
        if (best == null && current != null) {
            for (MediaController c : list) if (c.getSessionToken().equals(current.getSessionToken())) best = c;
        }
        if (best == null && !list.isEmpty()) best = list.get(0);
        if (best == null ? current == null : (current != null && best.getSessionToken().equals(current.getSessionToken()))) return;
        current = best;
        artSent = false;
        pushState();
    }

    private void pushState() {
        if (out == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "state");
            JSONArray ss = new JSONArray();
            for (MediaController sc : sessions) {
                JSONObject so = new JSONObject();
                PlaybackState ps = sc.getPlaybackState();
                so.put("pkg", sc.getPackageName());
                so.put("app", appLabel(sc.getPackageName()));
                so.put("state", ps == null ? -1 : ps.getState());
                ss.put(so);
            }
            o.put("sessions", ss);
            o.put("pinned", pinnedPkg == null ? "" : pinnedPkg);
            MediaController c = current;
            if (c == null) {
                o.put("none", true);
                send(o);
                return;
            }
            String pkg = c.getPackageName();
            o.put("pkg", pkg);
            o.put("app", appLabel(pkg));
            MediaMetadata m = c.getMetadata();
            if (m != null) {
                o.put("title", str(m, MediaMetadata.METADATA_KEY_TITLE));
                o.put("artist", str(m, MediaMetadata.METADATA_KEY_ARTIST));
                o.put("album", str(m, MediaMetadata.METADATA_KEY_ALBUM));
                o.put("dTitle", str(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                o.put("dSub", str(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                o.put("dDesc", str(m, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
                o.put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION));
                putArt(o, m);
            }
            PlaybackState s = c.getPlaybackState();
            if (s != null) {
                long pos = s.getPosition();
                boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
                if (playing && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
                o.put("pos", pos);
                o.put("playing", playing);
                o.put("speed", s.getPlaybackSpeed());
                o.put("actions", s.getActions());
            }
            List<MediaSession.QueueItem> q = c.getQueue();
            if (q != null && !q.isEmpty()) {
                JSONArray arr = new JSONArray();
                long active = s != null ? s.getActiveQueueItemId() : -1;
                for (int i = 0; i < Math.min(q.size(), 100); i++) {
                    MediaSession.QueueItem it = q.get(i);
                    JSONObject qi = new JSONObject();
                    qi.put("id", it.getQueueId());
                    CharSequence tt = it.getDescription().getTitle();
                    CharSequence st = it.getDescription().getSubtitle();
                    qi.put("title", tt == null ? "" : tt.toString());
                    qi.put("sub", st == null ? "" : st.toString());
                    qi.put("active", it.getQueueId() == active);
                    arr.put(qi);
                }
                o.put("queue", arr);
            }
            send(o);
        } catch (Exception e) {
            Log.w(TAG, "pushState", e);
        }
    }

    private void putArt(JSONObject o, MediaMetadata m) throws Exception {
        String key = str(m, MediaMetadata.METADATA_KEY_ALBUM) + "|" + str(m, MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        o.put("hasArt", bmp != null);
        if (bmp == null) return;
        key += "|" + bmp.getWidth() + "x" + bmp.getHeight() + "|" + bmp.getPixel(bmp.getWidth() / 2, bmp.getHeight() / 2);
        if (!key.equals(lastArtKey)) {
            int size = 320;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size * bmp.getHeight() / Math.max(1, bmp.getWidth()), true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            lastArtB64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            lastArtKey = key;
            artSent = false;
        }
        // 封面只在变化后发一次，节省带宽
        if (!artSent) {
            o.put("art", lastArtB64);
            o.put("artKey", lastArtKey);
            artSent = true;
        }
    }

    private boolean artSent;

    private static String str(MediaMetadata m, String k) {
        CharSequence cs = m.getText(k);
        return cs == null ? "" : cs.toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void handleCommand(JSONObject o) {
        if ("pin".equals(o.optString("c"))) {
            String p = o.optString("p");
            pinnedPkg = p.isEmpty() ? null : p;
            pickController();
            pushState();
            return;
        }
        MediaController c = current;
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        String cmd = o.optString("c");
        switch (cmd) {
            case "toggle": {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                break;
            }
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            case "seekBy": {
                PlaybackState s = c.getPlaybackState();
                if (s == null) break;
                long pos = s.getPosition();
                if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
                t.seekTo(Math.max(0, pos + o.optLong("v")));
                break;
            }
            case "seekTo": t.seekTo(o.optLong("v")); break;
            case "queue": t.skipToQueueItem(o.optLong("v")); break;
        }
    }

    // ---------------- 蓝牙 ----------------

    /**
     * 主手机只做蓝牙服务端：后台挂着等副屏来连，自己从不主动发起连接（省电）。
     * 副屏 App 在前台时才会来连，退到后台就断开。
     */
    @SuppressLint("MissingPermission")
    private void serverLoop() {
        BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
        BluetoothServerSocket server = null;
        while (running) {
            if (ad == null) { status = "本机没有蓝牙"; return; }
            try {
                if (!ad.isEnabled()) { status = "蓝牙未开启"; server = null; sleep(30_000); continue; }
                if (server == null) server = ad.listenUsingRfcommWithServiceRecord(Proto.SDP_NAME, Proto.UUID_SPP);
                status = "待机中，等待副屏连接（不耗电）";
                BluetoothSocket s = server.accept();
                closeSocket();
                socket = s;
                out = s.getOutputStream();
                status = "已连接 " + s.getRemoteDevice().getName();
                artSent = false;
                h.post(() -> { pickController(); pushState(); if (nav != null) nav.resend(); });
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = r.readLine()) != null) {
                    try {
                        JSONObject cmd = new JSONObject(line);
                        if ("cmd".equals(cmd.optString("t"))) h.post(() -> handleCommand(cmd));
                        else if ("hello".equals(cmd.optString("t"))
                                && cmd.optInt("ver") < Updater.myVersion(this)) {
                            new Thread(this::pushApkToDisplay, "apk-push").start();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (SecurityException e) {
                status = "缺少蓝牙权限，请在 App 里授权";
                sleep(10_000);
            } catch (Exception e) {
                // 连接断开或监听失效：重建监听
                try { if (server != null) server.close(); } catch (Exception ignored) {}
                server = null;
                sleep(1000);
            }
            closeSocket();
        }
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }


    /** 副屏版本比主手机旧：把主手机自己装着的 APK 通过蓝牙发过去，副屏 root 静默安装。 */
    private void pushApkToDisplay() {
        try {
            java.io.File apk = new java.io.File(getPackageCodePath());
            byte[] data = java.nio.file.Files.readAllBytes(apk.toPath());
            JSONObject head = new JSONObject();
            head.put("t", "apk");
            head.put("size", data.length);
            head.put("sha256", Updater.sha256(data));
            head.put("ver", Updater.myVersion(this));
            send(head);
            int chunk = 12 * 1024;
            for (int i = 0; i < data.length && out != null; i += chunk) {
                byte[] part = java.util.Arrays.copyOfRange(data, i, Math.min(data.length, i + chunk));
                JSONObject c = new JSONObject();
                c.put("t", "chunk");
                c.put("d", Base64.encodeToString(part, Base64.NO_WRAP));
                send(c);
            }
            JSONObject end = new JSONObject();
            end.put("t", "apkEnd");
            send(end);
        } catch (Exception e) {
            Log.w(TAG, "pushApk", e);
        }
    }

    /** 每 12 小时查一次新版本，有就发通知，点开设置页更新。 */
    private void scheduleUpdateCheck(long delay) {
        h.postDelayed(() -> {
            if (!running) return;
            new Thread(() -> {
                Updater.Info info = Updater.fetchLatest();
                if (info != null && info.versionCode > Updater.myVersion(this)) {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    nm.createNotificationChannel(new NotificationChannel("update", "版本更新", NotificationManager.IMPORTANCE_DEFAULT));
                    PendingIntent pi = PendingIntent.getActivity(this, 1,
                            new Intent(this, SourceActivity.class).putExtra("update", true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    nm.notify(2, new Notification.Builder(this, "update")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("手机副屏有新版 v" + info.versionName)
                            .setContentText(info.notes.isEmpty() ? "点击更新" : info.notes)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build());
                }
            }).start();
            scheduleUpdateCheck(12 * 3600_000L);
        }, delay);
    }

    private synchronized void send(JSONObject o) {
        OutputStream os = out;
        if (os == null) return;
        try {
            os.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            closeSocket();
        }
    }

    private void closeSocket() {
        out = null;
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
