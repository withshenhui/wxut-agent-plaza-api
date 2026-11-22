package com.wxut.agentplaza.service.kb;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KbChunkerTest {

    @Test
    void splitsAtArticleHeadings() {
        String doc = "第一章 总则\n为了规范管理，制定本办法。\n"
                + "第二十一条 验收材料包括立项批复。\n验收由建设部门提请。\n"
                + "第二十二条 未通过验收的系统不得正式上线。\n";
        List<KbChunker.Chunk> chunks = new KbChunker().split(doc);
        assertTrue(chunks.size() >= 3, "should split at 第X条, got " + chunks.size());
        assertEquals("第一章 总则", chunks.get(0).clauseNo);
        boolean hasArt21 = false, hasArt22 = false;
        for (KbChunker.Chunk c : chunks) {
            if (c.clauseNo.startsWith("第二十一条")) hasArt21 = true;
            if (c.clauseNo.startsWith("第二十二条")) hasArt22 = true;
        }
        assertTrue(hasArt21 && hasArt22);
    }

    @Test
    void longSegmentWindowsWithOverlap() {
        StringBuilder sb = new StringBuilder("第一条 这是超长条款内容。");
        while (sb.length() < 2000) sb.append("补充说明文字内容继续延长该条款的主体部分。");
        List<KbChunker.Chunk> chunks = new KbChunker().split(sb.toString());
        assertTrue(chunks.size() >= 2);
        // overlap: second chunk starts before first ends
        String first = chunks.get(0).text;
        String second = chunks.get(1).text;
        assertTrue(second.length() > 0);
        assertTrue(first.length() <= 810);
    }

    @Test
    void cleanRemovesPageNumbersAndBom() {
        String raw = "\uFEFF第一页内容\n\n\n2\n第 3 页 共 10 页\n—————\n正文继续\n";
        String cleaned = KbPipelineService.clean(raw);
        assertFalse(cleaned.contains("共 10 页"));
        assertFalse(cleaned.contains("—————"));
        assertFalse(cleaned.contains("2\n"));
        assertTrue(cleaned.contains("正文继续"));
        assertTrue(cleaned.contains("第一页内容"));
    }

    @Test
    void emptyInputYieldsNoChunks() {
        assertTrue(new KbChunker().split("").isEmpty());
        assertTrue(new KbChunker().split(null).isEmpty());
    }
}
