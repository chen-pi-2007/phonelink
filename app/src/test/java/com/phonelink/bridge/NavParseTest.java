package com.phonelink.bridge;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class NavParseTest {
    @Test public void splitDistanceAndAction() {
        List<String> l = Arrays.asList("300米", "左转", "进入人民路", "剩余5.2公里", "18分钟");
        String title = NavBridge.pickTitle(l, "");
        assertEquals("300米 左转", title);
        assertEquals("进入人民路", NavBridge.pickRoad(l, title));
        assertEquals("剩余5.2公里 · 18分钟", NavBridge.pickRemain(l, ""));
    }

    @Test public void roadWithoutPrefix() {
        List<String> l = Arrays.asList("高德地图", "1.2公里后右转", "中山大道");
        String title = NavBridge.pickTitle(l, "x");
        assertEquals("1.2公里后右转", title);
        assertEquals("中山大道", NavBridge.pickRoad(l, title));
    }

    @Test public void fallbackWhenNoDistance() {
        assertEquals("正在导航", NavBridge.pickTitle(Arrays.asList("高德地图"), "正在导航"));
    }
}
