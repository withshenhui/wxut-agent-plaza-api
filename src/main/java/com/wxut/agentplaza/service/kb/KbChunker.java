package com.wxut.agentplaza.service.kb;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title-aware chunker for Chinese policy documents.
 * Splits at chapter/article headings when present; falls back to fixed windows.
 */
@Service
public class KbChunker {

    public static class Chunk {
        public final String text;
        public final String clauseNo;
        public Chunk(String text, String clauseNo) { this.text = text; this.clauseNo = clauseNo; }
    }

    private static final int TARGET = 650;     // 500-800
    private static final int OVERLAP = 50;

    /** 第X章/第X节/第X条/一、二、/（一）（二） */
    private static final Pattern HEADING = Pattern.compile(
            "(?=^(?:第[一二三四五六七八九十百零\\d]+[章节条]|[一二三四五六七八九十]+、|（[一二三四五六七八九十]+）)[^\\n]{0,60}$)",
            Pattern.MULTILINE);

    public List<Chunk> split(String cleaned) {
        List<Chunk> out = new ArrayList<Chunk>();
        if (cleaned == null || cleaned.trim().isEmpty()) return out;

        // heading-aware segments
        List<int[]> marks = new ArrayList<int[]>();
        Matcher m = HEADING.matcher(cleaned);
        while (m.find()) marks.add(new int[]{m.start(), m.end()});
        List<String> segments = new ArrayList<String>();
        if (marks.size() >= 2) {
            int prev = 0;
            for (int[] mk : marks) {
                if (mk[0] > prev) segments.add(cleaned.substring(prev, mk[0]));
                prev = mk[0];
            }
            segments.add(cleaned.substring(prev));
        } else {
            segments.add(cleaned);
        }

        for (String seg : segments) {
            if (seg.trim().isEmpty()) continue;
            String clause = clauseOf(seg);
            if (seg.length() <= TARGET + 150) {
                out.add(new Chunk(seg.trim(), clause));
            } else {
                // fixed window with overlap inside a long segment
                int i = 0;
                while (i < seg.length()) {
                    int end = Math.min(i + TARGET, seg.length());
                    out.add(new Chunk(seg.substring(i, end).trim(), clause));
                    if (end >= seg.length()) break;
                    i = end - OVERLAP;
                }
            }
        }
        return out;
    }

    /** First heading line of the segment, e.g. "第四章 运维响应" or "第二十一条". */
    private static String clauseOf(String seg) {
        Matcher h = Pattern.compile("^(第[一二三四五六七八九十百零\\d]+[章节条]|[一二三四五六七八九十]+、)[^\\n]{0,50}")
                .matcher(seg);
        if (h.find()) {
            String c = h.group().trim();
            return c.length() > 60 ? c.substring(0, 60) : c;
        }
        return "";
    }
}
