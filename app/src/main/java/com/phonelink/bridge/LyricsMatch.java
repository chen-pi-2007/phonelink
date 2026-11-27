package com.phonelink.bridge;

import java.util.List;
import java.util.Locale;

/** 搜索结果匹配打分：标题归一化后相同 100 / 包含 60，歌手有交集 +40。 */
final class LyricsMatch {
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[（(\\[【].*?[)）\\]】]", "")   // 去括号内容：(Live)、(Explicit)
                .replaceAll("\\s+-\\s+.*$", "")             // 去 “ - 伴奏” 这类后缀
                .replaceAll("\\s+", "");
    }

    /** 搜索用的歌名：标题里有《》（翻唱上传常见“某某《歌名》”）时取书名号里的内容 */
    static String searchTitle(String title) {
        if (title == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("《([^》]+)》").matcher(title);
        return m.find() ? m.group(1).trim() : title.trim();
    }

    /** 时长比对：相差 2.5 秒内多半是同一版本 +30；相差 8 秒以上多半是别的版本（Live/剪辑）-60；任一未知不影响 */
    static int durationBonus(long myDurMs, long candDurMs) {
        if (myDurMs <= 0 || candDurMs <= 0) return 0;
        long diff = Math.abs(myDurMs - candDurMs);
        if (diff <= 2500) return 30;
        if (diff >= 8000) return -60;
        return 0;
    }

    static int score(String title, String artist, String candTitle, List<String> candArtists) {
        String a = normalize(title), b = normalize(candTitle);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int s;
        if (a.equals(b)) s = 100;
        else if (a.contains(b) || b.contains(a)) s = 60;
        else return 0;
        if (artist != null && candArtists != null) {
            outer:
            for (String mine : artist.split("[/、,，&]")) {
                String m = normalize(mine);
                if (m.isEmpty()) continue;
                for (String c : candArtists) {
                    String n = normalize(c);
                    if (!n.isEmpty() && (m.contains(n) || n.contains(m))) { s += 40; break outer; }
                }
            }
        }
        return s;
    }

    private LyricsMatch() {}
}
