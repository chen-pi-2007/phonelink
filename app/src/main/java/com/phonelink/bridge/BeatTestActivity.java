package com.phonelink.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** 律动测试：分别试“系统频谱接口”和“音频播放捕获”，看在这台手机 + 当前播放 App 上有没有数据。 */
public class BeatTestActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView vizText, capText;
    private ProgressBar vizBar, capBar;
    private Visualizer viz;
    private volatile float vizLevel;
    private volatile long vizFrames, vizNonZero;
    private String vizStatus = "未开始";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);

        TextView tip = new TextView(this);
        tip.setTextSize(16);
        tip.setText("先在音乐/听书 App 里放着声音，再分别点下面两个测试。条子随声音跳动 = 这条路可用。");
        root.addView(tip);

        Button b1 = new Button(this);
        b1.setText("测试 1：系统频谱接口（需麦克风权限名义，不会录麦克风）");
        b1.setAllCaps(false);
        b1.setOnClickListener(v -> startViz());
        root.addView(b1);
        vizBar = bar();
        root.addView(vizBar);
        vizText = new TextView(this);
        root.addView(vizText);

        Button b2 = new Button(this);
        b2.setText("测试 2：音频播放捕获（会弹一次录屏确认）");
        b2.setAllCaps(false);
        b2.setOnClickListener(v -> startCapture());
        root.addView(b2);
        capBar = bar();
        root.addView(capBar);
        capText = new TextView(this);
        root.addView(capText);

        Button tone = new Button(this);
        tone.setText("对照：本 App 自己放“嘀嘀”测试音（排除代码问题）");
        tone.setAllCaps(false);
        tone.setOnClickListener(v -> toggleTone());
        root.addView(tone);

        Button stop = new Button(this);
        stop.setText("停止捕获会话");
        stop.setOnClickListener(v -> stopService(new Intent(this, CaptureService.class)));
        root.addView(stop);

        setContentView(root);
        ui.post(tick);
    }

    private volatile boolean toneOn;

    /** 用标准“媒体”用途播放测试音：如果这都录不到，就是系统整体拦截；录得到，说明是别的 App 拒绝被录。 */
    private void toggleTone() {
        if (toneOn) { toneOn = false; return; }
        toneOn = true;
        new Thread(() -> {
            int rate = 16000;
            android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(rate)
                    .build();
            t.play();
            short[] buf = new short[rate / 2];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = i < rate * 0.15 ? (short) (12000 * Math.sin(2 * Math.PI * 440 * i / rate)) : 0;
            }
            while (toneOn) t.write(buf, 0, buf.length);
            t.stop();
            t.release();
        }, "tone").start();
    }

    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setMinimumHeight(60);
        return p;
    }

    private void startViz() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        try {
            if (viz != null) viz.release();
            viz = new Visualizer(0);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) {
                    double sum = 0;
                    for (byte x : w) { int s = (x & 0xff) - 128; sum += s * s; }
                    float rms = (float) Math.sqrt(sum / w.length) / 128f;
                    vizLevel = Math.min(1f, rms * 3f);
                    vizFrames++;
                    if (rms > 0.002f) vizNonZero++;
                }
                @Override public void onFftDataCapture(Visualizer v, byte[] f, int rate) {}
            }, Visualizer.getMaxCaptureRate(), true, false);
            viz.setEnabled(true);
            vizStatus = "运行中";
        } catch (Throwable e) {
            vizStatus = "失败：" + e;
        }
    }

    private void startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), 7);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 7) return;
        if (res != RESULT_OK || data == null) { CaptureService.status = "用户拒绝了录屏授权"; return; }
        Intent i = new Intent(this, CaptureService.class).putExtra("code", res).putExtra("data", data);
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            if (rc == 1) startViz(); else startCapture();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            vizBar.setProgress((int) (vizLevel * 100));
            vizText.setText(String.format(Locale.US, "频谱接口：%s  帧数 %d  有声帧 %d（%s）",
                    vizStatus, vizFrames, vizNonZero, verdict(vizFrames, vizNonZero)));
            capBar.setProgress((int) (CaptureService.level * 100));
            capText.setText(String.format(Locale.US, "播放捕获：%s  帧数 %d  有声帧 %d（%s）",
                    CaptureService.status, CaptureService.frames, CaptureService.nonZero,
                    verdict(CaptureService.frames, CaptureService.nonZero)));
            ui.postDelayed(this, 100);
        }
    };

    private static String verdict(long frames, long nonZero) {
        if (frames < 20) return "数据不足";
        return nonZero * 5 > frames ? "✔ 可用" : "✘ 全是静音";
    }

    @Override
    protected void onDestroy() {
        if (viz != null) viz.release();
        ui.removeCallbacks(tick);
        super.onDestroy();
    }
}
