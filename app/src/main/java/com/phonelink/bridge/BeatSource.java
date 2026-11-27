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
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[0] * 2);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) {
                    double sum = 0;
                    for (byte x : w) { int s = (x & 0xff) - 128; sum += s * s; }
                    double rms = Math.sqrt(sum / Math.max(1, w.length)) / 128.0;
                    BeatDetector.Result r = detector.feed(rms, System.currentTimeMillis());
                    try {
                        JSONObject o = new JSONObject();
                        o.put("t", "beat");
                        o.put("e", Math.round(r.e * 100) / 100.0);
                        o.put("onset", r.onset);
                        sink.send(o);
                    } catch (Exception ignored) {}
                }
                @Override public void onFftDataCapture(Visualizer v, byte[] f, int rate) {}
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), true, false); // 约 20 次/秒
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
