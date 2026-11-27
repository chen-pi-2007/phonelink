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
