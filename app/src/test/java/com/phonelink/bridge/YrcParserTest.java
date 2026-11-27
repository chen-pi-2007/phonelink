package com.phonelink.bridge;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class YrcParserTest {
    @Test public void parsesWordTimings() {
        List<YrcParser.Line> ls = YrcParser.parse("{\"t\":0,\"c\":[{\"tx\":\"作词\"}]}\n[1000,800](1000,300,0)你(1300,500,0)好");
        assertEquals(1, ls.size());
        YrcParser.Line l = ls.get(0);
        assertEquals(1000, l.t);
        assertEquals(800, l.d);
        assertEquals("你好", l.text);
        assertEquals(2, l.words.size());
        assertEquals(1300, l.words.get(1).t);
        assertEquals(500, l.words.get(1).d);
        assertEquals("好", l.words.get(1).w);
    }

    @Test public void ignoresGarbage() {
        assertTrue(YrcParser.parse("").isEmpty());
        assertTrue(YrcParser.parse(null).isEmpty());
        assertEquals(1, YrcParser.parse("hello\n[0,100](0,100,0)a").size());
    }

    @Test public void matchNormalizesTitlesAndArtists() {
        assertTrue(LyricsMatch.score("大风吹", "草东没有派对", "大风吹 (Live)", Arrays.asList("草东没有派对")) >= 140);
        assertEquals(0, LyricsMatch.score("大风吹", "X", "晴天", Arrays.asList("周杰伦")));
        assertTrue(LyricsMatch.score("晴天", "周杰伦/某某", "晴天", Arrays.asList("周杰伦")) >= 140);
        assertEquals("晴天", LyricsMatch.normalize(" 晴天 - 伴奏 "));
        // 翻唱上传常见格式：标题里夹人名和书名号，取《》里的歌名
        assertEquals("人生起起落落", LyricsMatch.searchTitle("周子洋、仔仔《人生起起落落》（翻唱）"));
        assertEquals("晴天", LyricsMatch.searchTitle("晴天"));
    }
}
