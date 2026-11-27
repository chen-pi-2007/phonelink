package com.phonelink.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * App 内更新。
 * 版本清单 update.json 同时放在 Gitee 和 GitHub，哪个能访问用哪个，取版本号最大的。
 * 主手机：下载后弹一次系统安装确认（覆盖安装，设置保留）。
 * 副屏：root 静默安装并自动重新打开。
 */
final class Updater {
    static final String GITEE_USER = "Chenpi167";
    static final String REPO = "phonelink";

    static final String[] MANIFESTS = {
            "https://gitee.com/" + GITEE_USER + "/" + REPO + "/raw/main/update.json",
            "https://raw.githubusercontent.com/chen-pi-2007/" + REPO + "/main/update.json",
            "https://ghfast.top/https://raw.githubusercontent.com/chen-pi-2007/" + REPO + "/main/update.json",
    };

    static final class Info {
        int versionCode;
        String versionName, notes, sha256;
        String[] apkUrls;
    }

    interface Progress { void on(String msg); }

    static int myVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String myVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 读取最新版本信息；全部失败返回 null。网络操作，勿在主线程调用。 */
    static Info fetchLatest() {
        Info best = null;
        for (String u : MANIFESTS) {
            try {
                String body = new String(get(u, 8000), StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(body);
                Info i = new Info();
                i.versionCode = o.getInt("versionCode");
                i.versionName = o.optString("versionName");
                i.notes = o.optString("notes");
                i.sha256 = o.getString("sha256").toLowerCase();
                JSONArray a = o.getJSONArray("apk");
                i.apkUrls = new String[a.length()];
                for (int k = 0; k < a.length(); k++) i.apkUrls[k] = a.getString(k);
                if (best == null || i.versionCode > best.versionCode) best = i;
            } catch (Exception ignored) {}
        }
        return best;
    }

    /** 下载并校验 sha256，成功返回文件。 */
    static File download(Context c, Info info, Progress p) throws Exception {
        File f = new File(c.getCacheDir(), "update.apk");
        Exception last = null;
        for (String u : info.apkUrls) {
            try {
                p.on("正在下载…");
                byte[] data = get(u, 30000);
                if (!sha256(data).equals(info.sha256)) throw new Exception("校验失败");
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                return f;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("没有下载地址") : last;
    }

    /** 主手机：弹系统安装确认。返回 false 表示需先开“安装未知应用”权限（已跳转设置）。 */
    static boolean promptInstall(Activity a, File apk) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        Uri uri = Uri.parse("content://" + a.getPackageName() + ".apk/update.apk");
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
        return true;
    }

    /** 副屏：root 静默覆盖安装，装完自动重新打开副屏界面。 */
    static void installRoot(Context c, File apk) throws Exception {
        File tmp = new File("/data/local/tmp/phonelink-update.apk");
        String cmd = "cp '" + apk.getAbsolutePath() + "' " + tmp
                + " && chmod 644 " + tmp
                + " && (pm install -r " + tmp + "; rm -f " + tmp
                + "; am start -n " + c.getPackageName() + "/.DisplayActivity) >/dev/null 2>&1 &";
        Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
    }

    /**
     * 后台安装：用 PackageInstaller 会话自我更新。安卓 12+ 若本 App 是“安装来源”则免确认静默安装；
     * 否则系统回调 STATUS_PENDING_USER_ACTION，由 InstallResultReceiver 发通知让用户点一下。
     */
    static void installSession(Context c, File apk) throws Exception {
        android.content.pm.PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams p = new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); java.io.OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, InstallResultReceiver.class).setAction("com.phonelink.bridge.INSTALL_RESULT");
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
            s.commit(android.app.PendingIntent.getBroadcast(c, id, cb, flags).getIntentSender());
        }
    }

    static String sha256(byte[] d) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(d));
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] get(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PhoneLink-Updater");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private Updater() {}
}
