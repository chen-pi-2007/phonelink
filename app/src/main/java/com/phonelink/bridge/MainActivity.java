package com.phonelink.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String mode = Proto.prefs(this).getString(Proto.KEY_MODE, null);
        if (getIntent().getBooleanExtra("choose", false)) mode = null;
        if ("display".equals(mode)) { go(DisplayActivity.class); return; }
        if ("source".equals(mode)) { go(SourceActivity.class); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView t = new TextView(this);
        t.setText("这台手机是？");
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        root.addView(t);

        Button src = new Button(this);
        src.setText("主手机（在放歌 / 听书）");
        src.setTextSize(20);
        src.setOnClickListener(v -> { save("source"); go(SourceActivity.class); });
        root.addView(src);

        Button dsp = new Button(this);
        dsp.setText("副屏（装在车把上）");
        dsp.setTextSize(20);
        dsp.setOnClickListener(v -> { save("display"); go(DisplayActivity.class); });
        root.addView(dsp);

        setContentView(root);
    }

    private void save(String m) { Proto.prefs(this).edit().putString(Proto.KEY_MODE, m).apply(); }

    private void go(Class<?> c) { startActivity(new Intent(this, c)); finish(); }
}
