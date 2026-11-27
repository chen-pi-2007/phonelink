package com.phonelink.bridge;

import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 按歌名 + 歌手去网易云搜歌词。接口非官方：任何失败都静默返回“没有歌词”，不影响其他功能。
 * 结果是一条可直接发给副屏的 lyrics 消息：有逐字歌词发 lines，否则发原始 lrc，找不到发空 lines。
 */
final class LyricsFetcher {
    interface Callback { void onLyrics(String key, JSONObject msg); }

    private static final String TAG = "PhoneLinkLyrics";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private final LruCache<String, JSONObject> cache = new LruCache<>(50);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    static String key(String title, String artist) { return (title == null ? "" : title) + "|" + (artist == null ? "" : artist); }

    void fetch(String title, String artist, Callback cb) {
        String key = key(title, artist);
        JSONObject hit = cache.get(key);
        if (hit != null) { cb.onLyrics(key, hit); return; }
        pool.execute(() -> {
            JSONObject msg = load(key, title, artist);
            cache.put(key, msg);
            cb.onLyrics(key, msg);
        });
    }

    private JSONObject load(String key, String title, String artist) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("t", "lyrics");
            msg.put("key", key);
            msg.put("lines", new JSONArray());
            long id = search(title, artist);
            if (id <= 0) return msg;
            JSONObject l = new JSONObject(get("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&tv=-1&yv=1"));
            String yrc = l.optJSONObject("yrc") != null ? l.optJSONObject("yrc").optString("lyric") : "";
            List<YrcParser.Line> lines = YrcParser.parse(yrc);
            if (!lines.isEmpty()) {
                msg.put("lines", toJson(lines));
            } else {
                String lrc = l.optJSONObject("lrc") != null ? l.optJSONObject("lrc").optString("lyric") : "";
                if (!lrc.isEmpty()) msg.put("lrc", stripCredits(lrc));
            }
        } catch (Exception e) {
            Log.w(TAG, "lyrics failed: " + e);
        }
        return msg;
    }

    private long search(String title, String artist) throws Exception {
        String q = URLEncoder.encode(title + " " + (artist == null ? "" : artist), "UTF-8");
        JSONObject r = new JSONObject(get("https://music.163.com/api/search/get/web?type=1&limit=6&s=" + q));
        JSONArray songs = r.optJSONObject("result") == null ? null : r.optJSONObject("result").optJSONArray("songs");
        if (songs == null) return -1;
        long best = -1;
        int bestScore = 59; // 至少标题包含才算
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.getJSONObject(i);
            List<String> artists = new ArrayList<>();
            JSONArray ar = s.optJSONArray("artists");
            if (ar != null) for (int k = 0; k < ar.length(); k++) artists.add(ar.getJSONObject(k).optString("name"));
            int sc = LyricsMatch.score(title, artist, s.optString("name"), artists);
            if (sc > bestScore) { bestScore = sc; best = s.optLong("id"); }
        }
        return best;
    }

    /** 去掉开头“作词 : xx”“作曲 : xx”这类署名行，避免当成歌词滚动 */
    private static String stripCredits(String lrc) {
        StringBuilder sb = new StringBuilder();
        for (String line : lrc.split("\n")) {
            String body = line.replaceAll("^(\\[[^\\]]*\\])+", "").trim();
            if (body.matches("^(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|发行|OP|SP|词|曲)\\s*[:：].*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static JSONArray toJson(List<YrcParser.Line> lines) throws Exception {
        JSONArray arr = new JSONArray();
        for (YrcParser.Line l : lines) {
            JSONObject o = new JSONObject();
            o.put("t", l.t);
            o.put("d", l.d);
            o.put("text", l.text);
            JSONArray ws = new JSONArray();
            for (YrcParser.Word w : l.words) {
                JSONObject wo = new JSONObject();
                wo.put("t", w.t);
                wo.put("d", w.d);
                wo.put("w", w.w);
                ws.put(wo);
            }
            o.put("words", ws);
            arr.put(o);
        }
        return arr;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://music.163.com/");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
