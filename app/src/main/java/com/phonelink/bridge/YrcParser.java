package com.phonelink.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 网易云逐字歌词（yrc）解析：[行开始,行时长](字开始,字时长,0)字... 纯 Java，可单元测试。 */
final class YrcParser {
    static final class Word { long t, d; String w; }
    static final class Line { long t, d; String text; List<Word> words = new ArrayList<>(); }

    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");

    static List<Line> parse(String yrc) {
        List<Line> out = new ArrayList<>();
        if (yrc == null) return out;
        for (String raw : yrc.split("\\r?\\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue; // 跳过 {"t":..} 元信息行和其他杂项
            Line l = new Line();
            l.t = Long.parseLong(m.group(1));
            l.d = Long.parseLong(m.group(2));
            StringBuilder text = new StringBuilder();
            Matcher w = WORD.matcher(m.group(3));
            while (w.find()) {
                Word word = new Word();
                word.t = Long.parseLong(w.group(1));
                word.d = Long.parseLong(w.group(2));
                word.w = w.group(3);
                l.words.add(word);
                text.append(word.w);
            }
            l.text = text.toString();
            out.add(l);
        }
        return out;
    }

    private YrcParser() {}
}
