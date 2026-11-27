package com.phonelink.bridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/**
 * 系统“音频播放捕获”：用户同意一次录屏授权后，会话一直挂着；
 * 只在需要时读取声音算音量（律动），平时不读，几乎不耗电。
 */
public class CaptureService extends Service {
    static volatile float level;        // 最近一帧音量 0..1
    static volatile long frames, nonZero;
    static volatile String status = "未启动";
    static volatile boolean active;     // 会话是否挂着

    private MediaProjection projection;
    private AudioRecord rec;
    private volatile boolean reading;

    @Override public IBinder onBind(Intent i) { return null; }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture", "律动捕获", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("律动捕获会话已挂起")
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(3, n);
        }
        if (intent == null || Build.VERSION.SDK_INT < 29) {
            status = "系统版本过低（需安卓 10+）";
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        if (data == null) { status = "没有授权数据"; return START_NOT_STICKY; }
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { active = false; status = "会话被系统结束"; stopReading(); }
            }, null);
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(16000)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            active = true;
            status = "会话已挂起（未读取）";
            startReading(); // 测试页：直接开始读；正式版只在“已连接+白名单在播放”时读
        } catch (Exception e) {
            status = "启动失败：" + e;
        }
        return START_NOT_STICKY;
    }

    void startReading() {
        if (rec == null || reading) return;
        reading = true;
        frames = 0;
        nonZero = 0;
        rec.startRecording();
        new Thread(() -> {
            short[] buf = new short[800]; // 50ms
            while (reading) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                float rms = (float) Math.sqrt(sum / n) / 32768f;
                level = Math.min(1f, rms * 4f);
                frames++;
                if (rms > 0.001f) nonZero++;
            }
        }, "capture-read").start();
        status = "正在读取";
    }

    void stopReading() {
        reading = false;
        try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopReading();
        active = false;
        if (rec != null) rec.release();
        if (projection != null) projection.stop();
        status = "已停止";
        super.onDestroy();
    }
}
