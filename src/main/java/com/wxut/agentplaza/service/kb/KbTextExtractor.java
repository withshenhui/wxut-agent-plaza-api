package com.wxut.agentplaza.service.kb;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Extract plain text from pdf/docx/txt/md. */
@Service
public class KbTextExtractor {

    public String extract(Path file, String ext) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return extract(in, ext);
        }
    }

    public String extract(InputStream in, String ext) throws Exception {
        if ("docx".equals(ext)) return fromDocx(in);
        if ("pdf".equals(ext)) return fromPdf(in);
        // txt / md
        byte[] all = readAll(in);
        return new String(all, StandardCharsets.UTF_8);
    }

    private String fromDocx(InputStream in) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.trim().isEmpty()) sb.append(t.trim()).append('\n');
            }
            // tables
            doc.getTables().forEach(tb -> tb.getRows().forEach(r -> {
                List<String> cells = new ArrayList<String>();
                r.getTableCells().forEach(c -> {
                    String t = c.getText();
                    cells.add(t == null ? "" : t.trim());
                });
                sb.append(String.join(" | ", cells)).append('\n');
            }));
            return sb.toString();
        }
    }

    private String fromPdf(InputStream in) throws Exception {
        try (PDDocument doc = PDDocument.load(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }
}
