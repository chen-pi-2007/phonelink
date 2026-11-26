package com.phonelink.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 副屏开机自启：后台无法直接起 Activity，借 root 用 am start 拉起。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!"display".equals(Proto.prefs(c).getString(Proto.KEY_MODE, null))) return;
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "am start -n " + c.getPackageName() + "/.DisplayActivity"}).waitFor();
            } catch (Exception ignored) {}
        }).start();
    }
}
