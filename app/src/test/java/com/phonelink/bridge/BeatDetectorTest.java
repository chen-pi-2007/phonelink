package com.phonelink.bridge;

import static org.junit.Assert.*;

import org.junit.Test;

public class BeatDetectorTest {
    @Test public void constantEnergyNeverOnsets() {
        BeatDetector d = new BeatDetector();
        for (int i = 0; i < 200; i++) assertFalse(d.feed(0.3, i * 50L).onset);
    }

    @Test public void spikesEvery500msOnsetOnlyOnSpikes() {
        BeatDetector d = new BeatDetector();
        int onsets = 0;
        for (int i = 0; i < 200; i++) {
            long t = i * 50L;
            boolean spike = i % 10 == 0 && i > 20;
            BeatDetector.Result r = d.feed(spike ? 0.8 : 0.1, t);
            if (r.onset) { assertTrue("onset 只能出现在尖峰帧", spike); onsets++; }
        }
        assertTrue(onsets >= 15);
    }

    @Test public void refractoryBlocksDoubleTrigger() {
        BeatDetector d = new BeatDetector();
        for (int i = 0; i < 40; i++) d.feed(0.1, i * 50L);
        assertTrue(d.feed(0.8, 2000).onset);
        assertFalse(d.feed(0.1, 2050).onset);
        assertFalse(d.feed(0.8, 2100).onset); // 距上次 100ms，冷却中
    }

    @Test public void silenceGivesZero() {
        BeatDetector d = new BeatDetector();
        BeatDetector.Result r = null;
        for (int i = 0; i < 50; i++) r = d.feed(0, i * 50L);
        assertEquals(0, r.e, 1e-9);
        assertFalse(r.onset);
    }
}
