package com.phonelink.bridge;

import android.media.audiofx.Visualizer;
import android.util.Log;

import org.json.JSONObject;

/**
 * 律动来源：系统频谱接口（Visualizer，session 0 = 整机混音），不需要录屏授权。
 * 只在需要时 start()，不需要就 stop() 释放，省电。需要 RECORD_AUDIO 权限（只是权限名，不录麦克风）。
 */
final class BeatSource {
    interface Sink { void send(JSONObject beat); }

    private final Sink sink;
    private final BeatDetector detector = new BeatDetector();
    private Visualizer viz;

    BeatSource(Sink sink) { this.sink = sink; }

    boolean running() { return viz != null; }

    void start() {
        if (viz != null) return;
        try {
            viz = new Visualizer(0);
            viz.setCaptureSize(Math.min(1024, Visualizer.getCaptureSizeRange()[1])); // 频率分辨率足够分出低音
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) {}
                /** 只听低音鼓：取 FFT 中约 38~160Hz 的能量做节拍检测（人声和高频不触发） */
                @Override public void onFftDataCapture(Visualizer v, byte[] f, int samplingRate) {
                    int n = f.length / 2;                       // 复数点数
                    double binHz = samplingRate / 1000.0 / f.length; // samplingRate 单位是毫赫兹
                    int lo = Math.max(1, (int) (38 / binHz)), hi = Math.max(lo + 1, Math.min(n - 1, (int) (160 / binHz)));
                    double sum = 0;
                    for (int k = lo; k <= hi; k++) {
                        double re = f[2 * k], im = f[2 * k + 1];
                        sum += Math.sqrt(re * re + im * im);
                    }
                    double bass = sum / ((hi - lo + 1) * 128.0);
                    BeatDetector.Result r = detector.feed(bass, System.currentTimeMillis());
                    try {
                        JSONObject o = new JSONObject();
                        o.put("t", "beat");
                        o.put("e", Math.round(r.e * 100) / 100.0);
                        o.put("onset", r.onset);
                        sink.send(o);
                    } catch (Exception ignored) {}
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true); // 约 20 次/秒
            viz.setEnabled(true);
        } catch (Throwable e) {
            Log.w("PhoneLinkBeat", "Visualizer unavailable: " + e);
            stop();
        }
    }

    void stop() {
        if (viz == null) return;
        try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) {}
        viz = null;
    }
}
