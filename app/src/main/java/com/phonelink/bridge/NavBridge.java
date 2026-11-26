package com.phonelink.bridge;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 导航信息桥：高德 / 百度 / 腾讯地图。
 * 1. 读地图 App 导航时的常驻通知（文字 + 大图标，大图标通常就是转向箭头）
 * 2. 高德车机标准广播 AUTONAVI_STANDARD_BROADCAST_SEND（手机版不一定发，发了就优先用）
 * 同时把原始数据记进 nav_log.txt，便于按实际字段做精细适配。
 */
final class NavBridge {
    interface Sink { void send(JSONObject o); }

    static final Set<String> MAP_PKGS = Set.of(
            "com.autonavi.minimap",      // 高德地图
            "com.autonavi.amapauto",     // 高德车机版
            "com.autonavi.amapautolite",
            "com.baidu.BaiduMap",        // 百度地图
            "com.baidu.naviauto",
            "com.tencent.map",           // 腾讯地图
            "com.sogou.map.android.maps");

    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final long LOG_LIMIT = 300 * 1024;

    private final Context ctx;
    private final Sink sink;
    private JSONObject last;
    private String lastNotifKey;
    private long lastBroadcastAt;
    private BroadcastReceiver amapReceiver;

    NavBridge(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
    }

    void start() {
        amapReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { onAmapBroadcast(i); }
        };
        IntentFilter f = new IntentFilter(AMAP_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(amapReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(amapReceiver, f);
        }
    }

    void stop() {
        try { if (amapReceiver != null) ctx.unregisterReceiver(amapReceiver); } catch (Exception ignored) {}
    }

    /** 副屏刚连上时补发当前导航状态。 */
    void resend() {
        if (last != null) sink.send(last);
    }

    void onPosted(StatusBarNotification sbn) {
        if (!MAP_PKGS.contains(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        log("notif " + sbn.getPackageName() + " ongoing=" + sbn.isOngoing() + " " + dump(ex));
        if (!sbn.isOngoing()) return;
        // 高德广播 3 秒内来过，就以广播为准
        if (System.currentTimeMillis() - lastBroadcastAt < 3000) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "nav");
            o.put("src", "notif");
            o.put("app", appName(sbn.getPackageName()));
            o.put("title", str(ex, Notification.EXTRA_TITLE));
            o.put("text", str(ex, Notification.EXTRA_TEXT));
            o.put("sub", str(ex, Notification.EXTRA_SUB_TEXT));
            o.put("big", str(ex, Notification.EXTRA_BIG_TEXT));
            o.put("info", str(ex, Notification.EXTRA_INFO_TEXT));
            String icon = iconB64(n);
            if (icon != null) o.put("icon", icon);
            lastNotifKey = sbn.getKey();
            emit(o);
        } catch (Exception ignored) {}
    }

    void onRemoved(StatusBarNotification sbn) {
        if (!sbn.getKey().equals(lastNotifKey)) return;
        lastNotifKey = null;
        end();
    }

    private void onAmapBroadcast(Intent i) {
        Bundle ex = i.getExtras();
        if (ex == null) return;
        log("amap-broadcast " + dump(ex));
        int type = ex.getInt("KEY_TYPE", -1);
        try {
            if (type == 10001) { // 引导信息
                lastBroadcastAt = System.currentTimeMillis();
                JSONObject o = new JSONObject();
                o.put("t", "nav");
                o.put("src", "amap");
                o.put("app", "高德地图");
                o.put("title", fmtDist(ex.getInt("SEG_REMAIN_DIS", -1)) + " " + turnName(ex.getInt("ICON", -1)));
                o.put("text", "进入 " + nz(ex.getString("NEXT_ROAD_NAME")));
                o.put("sub", "剩余 " + fmtDist(ex.getInt("ROUTE_REMAIN_DIS", -1))
                        + " · " + fmtTime(ex.getInt("ROUTE_REMAIN_TIME", -1)));
                o.put("iconId", ex.getInt("ICON", -1));
                emit(o);
            } else if (type == 10019) { // 导航状态：8/9/12 等表示导航结束或退出
                int state = ex.getInt("EXTRA_STATE", -1);
                if (state == 9 || state == 12 || state == 2) end();
            }
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        last = o;
        sink.send(o);
    }

    private void end() {
        last = null;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "nav");
            o.put("end", true);
            sink.send(o);
        } catch (Exception ignored) {}
    }

    // ---------------- 工具 ----------------

    private String iconB64(Notification n) {
        try {
            Drawable d = null;
            Icon large = n.getLargeIcon();
            if (large != null) d = large.loadDrawable(ctx);
            if (d == null) return null;
            int w = Math.max(1, Math.min(160, d.getIntrinsicWidth()));
            int h = Math.max(1, Math.min(160, d.getIntrinsicHeight()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String appName(String pkg) {
        if (pkg.startsWith("com.autonavi")) return "高德地图";
        if (pkg.startsWith("com.baidu")) return "百度地图";
        if (pkg.startsWith("com.tencent")) return "腾讯地图";
        return pkg;
    }

    private static String str(Bundle b, String k) {
        CharSequence cs = b.getCharSequence(k);
        return cs == null ? "" : cs.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String fmtDist(int m) {
        if (m < 0) return "";
        return m >= 1000 ? String.format(Locale.US, "%.1f公里", m / 1000f) : m + "米";
    }

    private static String fmtTime(int s) {
        if (s < 0) return "";
        int min = (s + 59) / 60;
        return min >= 60 ? (min / 60) + "小时" + (min % 60) + "分" : min + "分钟";
    }

    /** 高德 ICON 编号 -> 文字（常用部分）。 */
    private static String turnName(int icon) {
        switch (icon) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "向左前方";
            case 5: return "向右前方";
            case 6: return "向左后方";
            case 7: return "向右后方";
            case 8: return "掉头";
            case 9: return "直行";
            case 10: return "到达途经点";
            case 11: return "进入环岛";
            case 12: return "驶出环岛";
            case 15: return "到达目的地";
            default: return "";
        }
    }

    private static String dump(Bundle b) {
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v == null || v instanceof CharSequence || v instanceof Number || v instanceof Boolean) {
                sb.append(k).append('=').append(v).append("; ");
            } else {
                sb.append(k).append("=<").append(v.getClass().getSimpleName()).append(">; ");
            }
        }
        return sb.append('}').toString();
    }

    private String lastLog;

    private synchronized void log(String line) {
        if (line.equals(lastLog)) return; // 导航通知每秒刷新，相同内容不重复记
        lastLog = line;
        try {
            File f = logFile(ctx);
            if (f.length() > LOG_LIMIT) f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + line + "\n");
            }
        } catch (Exception ignored) {}
    }

    static File logFile(Context c) { return new File(c.getFilesDir(), "nav_log.txt"); }
}
