package com.phonelink.bridge;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;

/**
 * App 被覆盖更新后系统会发 MY_PACKAGE_REPLACED。
 * 安卓（vivo 尤其明显）更新后经常不重新绑定通知监听服务，看起来像“权限失效”，
 * 这里自动修复：禁用再启用服务组件，强制系统重新绑定（等同手动把开关关掉再打开）。
 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        String mode = Proto.prefs(c).getString(Proto.KEY_MODE, null);
        if ("source".equals(mode)) rebindListener(c);
        else if ("display".equals(mode)) {
            new Thread(() -> {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "am start -n " + c.getPackageName() + "/.DisplayActivity"}).waitFor();
                } catch (Exception ignored) {}
            }).start();
        }
    }

    static void rebindListener(Context c) {
        ComponentName cn = new ComponentName(c, BridgeListenerService.class);
        PackageManager pm = c.getPackageManager();
        try {
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
        try { NotificationListenerService.requestRebind(cn); } catch (Exception ignored) {}
    }
}
