package com.phonelink.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** 后台安装结果：需要用户确认时发通知，点一下完成更新（后台不能直接弹安装界面）。 */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return;
        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) return;
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("update", "版本更新", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(c, 3, confirm,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(2, new Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("手机副屏新版已下载")
                .setContentText("点击完成更新（之后的更新会自动安装）")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }
}
