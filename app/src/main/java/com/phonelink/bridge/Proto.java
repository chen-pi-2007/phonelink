package com.phonelink.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** 两端共用的常量。协议：每行一个 JSON。 */
final class Proto {
    static final UUID UUID_SPP = UUID.fromString("7f3c2a51-4b1e-4d8a-9c55-0e6a1b2c3d4e");
    static final String SDP_NAME = "PhoneLinkBridge";

    static final String PREFS = "phonelink";
    static final String KEY_MODE = "mode";          // "source" | "display"
    static final String KEY_TARGET = "target_mac";  // 主手机要连接的副屏蓝牙地址
    static final String KEY_HIDE_RECENTS = "hide_recents";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Proto() {}
}
