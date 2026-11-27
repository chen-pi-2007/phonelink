package com.phonelink.bridge;

import java.util.ArrayDeque;

/**
 * 节拍检测（纯 Java）：输入每帧音量能量，输出归一化强度和是否为节拍起点。
 * 强度 e = 当前能量 / 最近 3 秒峰值；起点 = 能量 > 最近 1 秒均值 × 1.35，且 > 0.02，且距上次起点 > 220ms。
 */
final class BeatDetector {
    static final class Result { double e; boolean onset; }

    private static final long PEAK_WINDOW = 3000, AVG_WINDOW = 1000, REFRACTORY = 220;
    private static final double RATIO = 1.35, FLOOR = 0.02;

    private final ArrayDeque<long[]> hist = new ArrayDeque<>(); // {时间, 能量×1e6}
    private long lastOnset = -REFRACTORY;

    Result feed(double energy, long now) {
        while (!hist.isEmpty() && now - hist.peekFirst()[0] > PEAK_WINDOW) hist.pollFirst();
        double peak = energy, sum = 0;
        int n = 0;
        for (long[] h : hist) {
            double v = h[1] / 1e6;
            if (v > peak) peak = v;
            if (now - h[0] <= AVG_WINDOW) { sum += v; n++; }
        }
        Result r = new Result();
        r.e = peak > 0 ? Math.min(1, energy / peak) : 0;
        double avg = n > 0 ? sum / n : energy;
        if (n > 0 && energy > FLOOR && energy > avg * RATIO && now - lastOnset > REFRACTORY) {
            r.onset = true;
            lastOnset = now;
        }
        hist.addLast(new long[]{now, Math.round(energy * 1e6)});
        return r;
    }
}
