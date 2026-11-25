package com.wxut.agentplaza.service.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.FormRecord;
import com.wxut.agentplaza.entity.FormTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能填报-原模板导出（POI XWPF）。
 * 在空白模板 docx 上原位填充：基本信息按单元格坐标写入（保留原字体字号），
 * 本人总结按段落拆分写入表1总结格与表2续篇格；意见签章区一律不碰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormExportService {

    private final FormAgentService agentService;
    private final ObjectMapper om = new ObjectMapper();

    /** 表1 总结格与表2 续篇格的段落分割比例（近似原版分页点） */
    private static final double SPLIT_RATIO = 0.55;

    /** 生成填充后的 docx 字节；模板无文件或类型不支持返回 null */
    public byte[] export(FormRecord record) throws Exception {
        FormTemplate tpl = agentService.getTemplateEntity(record.getTemplateId());
        if (tpl == null) throw new IllegalArgumentException("模板不存在");
        byte[] tplBytes = agentService.loadTemplateBytes(tpl);
        if (tplBytes == null) return null;

        Map<String, String> vals = fieldValues(record);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(tplBytes))) {
            List<XWPFTable> tables = doc.getTables();
            if (tables.size() < 2) throw new IllegalStateException("模板表格结构异常（少于 2 张表）");
            XWPFTable t1 = tables.get(0);
            XWPFTable t2 = tables.get(1);

            // 表1 行0-2：值单元格（列 1/3/5）
            String[][] basic = {
                    {"姓名", "性别", "出生年月"},
                    {"民族", "政治面貌", "文化程度"},
                    {"岗位类别", "岗位等级", "岗位名称"}
            };
            for (int r = 0; r < 3; r++) {
                XWPFTableRow row = t1.getRow(r);
                if (row == null) continue;
                for (int cIdx = 0; cIdx < 3; cIdx++) {
                    String label = basic[r][cIdx];
                    String val = vals.getOrDefault(label, "");
                    setCellTextPreserveStyle(row.getCell(cIdx * 2 + 1), val);
                }
            }

            // 本人总结：完整写入表1 行3 大格（与预览一致，连续不拆分）
            String summary = vals.getOrDefault("本 人 总 结", "");
            List<String> paras = splitParas(summary);
            XWPFTableCell sumCell = t1.getRow(3) != null ? t1.getRow(3).getCell(0) : null;
            if (sumCell != null) {
                clearKeepFirst(sumCell);
                for (String p : paras) {
                    addParaCopyStyle(sumCell, p);
                }
            }

            // 与预览版式对齐：删除表2 的续篇大格行，「本人签名：」改为两表之间的右对齐段落，
            // 表2（意见区）等比放大到与表1 同宽（消除模板固有的 ~2mm 右缘错位）
            if (t2.getRows().size() > 0 && isContRow(t2.getRow(0))) {
                t2.removeRow(0);
            }
            writeSignBetweenTables(doc, t1);
            alignTableWidth(t1, t2);
            // 意见区边框统一：模板部分行的列边界是 nil（无线），渲染出断续竖线——统一改 single 实线
            unifyNilBorders(t2);

            // 考核年度替换：模板副标题“（2025年度）”按大模型分析结果替换
            String khnf = "";
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fs = om.readValue(record.getFieldsJson(), List.class);
                for (Map<String, Object> f : fs) {
                    if ("_khnf".equals(String.valueOf(f.get("key"))) && f.get("val") != null) {
                        String v = String.valueOf(f.get("val")).trim().replaceAll("[^0-9]", "");
                        if (v.length() == 4) khnf = v;
                    }
                }
            } catch (Exception ignore) {
            }
            if (!khnf.isEmpty()) {
                replaceYearInDoc(doc, khnf);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** 续篇行判定：单格且跨度=整表（原模板表2行0 的总结续篇大格） */
    private boolean isContRow(XWPFTableRow row) {
        try {
            return row != null && row.getTableCells().size() == 1
                    && row.getCtRow().getTcArray().length == 1
                    && row.getCtRow().getTcArray()[0].getTcPr() != null
                    && row.getCtRow().getTcArray()[0].getTcPr().getGridSpan() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在表1 与表2 之间写右对齐的「本人签名：」段落（与预览 doc-sign 一致） */
    private void writeSignBetweenTables(XWPFDocument doc, XWPFTable t1) {
        try {
            java.util.List<org.apache.poi.xwpf.usermodel.IBodyElement> els = doc.getBodyElements();
            int t1Idx = -1;
            for (int i = 0; i < els.size(); i++) {
                if (els.get(i) instanceof XWPFTable && (XWPFTable) els.get(i) == t1) { t1Idx = i; break; }
            }
            if (t1Idx < 0) return;
            // 复用表间的既有空段落；没有则在表1 后插入新段
            XWPFParagraph p = null;
            for (int j = t1Idx + 1; j < els.size(); j++) {
                if (els.get(j) instanceof XWPFParagraph) { p = (XWPFParagraph) els.get(j); break; }
                if (els.get(j) instanceof XWPFTable) break;
            }
            if (p == null) {
                org.apache.xmlbeans.XmlCursor cur = t1.getCTTbl().newCursor();
                cur.toNextToken();
                p = doc.insertNewParagraph(cur);
            }
            for (int i = p.getRuns().size() - 1; i >= 0; i--) p.removeRun(i);
            p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
            p.setSpacingBefore(200);
            XWPFRun r = p.createRun();
            r.setFontFamily("仿宋_GB2312");
            r.setFontSize(12);
            r.setText("本人签名：");
        } catch (Exception e) {
            log.warn("智能填报：写签名段落失败: {}", e.getMessage());
        }
    }

    /** 表2 等比放大到与表1 同宽（tblGrid + 每行 tcW + tblW，比例保持，消除右缘错位） */
    private void alignTableWidth(XWPFTable t1, XWPFTable t2) {
        try {
            long w1 = tableWidth(t1), w2 = tableWidth(t2);
            if (w1 <= 0 || w2 <= 0 || w1 == w2) return;
            double scale = (double) w1 / w2;
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid grid = t2.getCTTbl().getTblGrid();
            if (grid != null) {
                for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGridCol col : grid.getGridColList()) {
                    if (col.isSetW()) col.setW(java.math.BigInteger.valueOf(Math.round(col.getW().longValue() * scale)));
                }
            }
            for (XWPFTableRow row : t2.getRows()) {
                for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc : row.getCtRow().getTcArray()) {
                    if (tc.getTcPr() != null && tc.getTcPr().isSetTcW() && tc.getTcPr().getTcW().isSetW()) {
                        tc.getTcPr().getTcW().setW(java.math.BigInteger.valueOf(Math.round(tc.getTcPr().getTcW().getW().longValue() * scale)));
                    }
                }
            }
            if (t2.getCTTbl().getTblPr() != null && t2.getCTTbl().getTblPr().getTblW() != null
                    && t2.getCTTbl().getTblPr().getTblW().isSetW()) {
                t2.getCTTbl().getTblPr().getTblW().setW(java.math.BigInteger.valueOf(w1));
            }
            // 左缩进对齐：模板原表2 用 -485 负缩进补偿窄表，扩宽后须与表1 的缩进一致，否则左缘错位 ~10mm
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr pr1 = t1.getCTTbl().getTblPr();
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr pr2 = t2.getCTTbl().getTblPr();
            if (pr1 != null && pr2 != null && pr1.getTblInd() != null && pr1.getTblInd().isSetW()) {
                if (pr2.getTblInd() == null) pr2.addNewTblInd();
                pr2.getTblInd().setW(pr1.getTblInd().getW());
                pr2.getTblInd().setType(pr1.getTblInd().getType());
            }
        } catch (Exception e) {
            log.warn("智能填报：对齐表宽失败: {}", e.getMessage());
        }
    }

    private long tableWidth(XWPFTable t) {
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid grid = t.getCTTbl().getTblGrid();
            if (grid == null) return 0;
            long w = 0;
            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGridCol col : grid.getGridColList()) {
                w += col.isSetW() ? col.getW().longValue() : 0;
            }
            return w;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 意见区标签列右边界统一为 single 实线（模板部分行为 nil，渲染出断续竖线似虚线）。
     * 只动每行第 1 格的 right 边框：内容区内部边界（nil）保持无线的原版视觉，与预览两列布局一致。
     */
    private void unifyNilBorders(XWPFTable t) {
        try {
            for (XWPFTableRow row : t.getRows()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc[] tcs = row.getCtRow().getTcArray();
                if (tcs.length == 0) continue;
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc first = tcs[0];
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr pr = first.getTcPr();
                if (pr == null) pr = first.addNewTcPr();
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders bs =
                        pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder right =
                        bs.getRight() != null ? bs.getRight() : bs.addNewRight();
                right.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
                right.setSz(java.math.BigInteger.valueOf(8));
                right.setColor("auto");
            }
        } catch (Exception e) {
            log.warn("智能填报：统一意见区边框失败: {}", e.getMessage());
        }
    }

    /** 全文把“2025年度”替换为实际考核年度（标题副题等在正文段落，不在表格内） */
    private void replaceYearInDoc(XWPFDocument doc, String khnf) {
        java.util.function.Consumer<XWPFParagraph> fix = p -> {
            String t = p.getText();
            if (t == null || !t.contains("2025年度")) return;
            String nt = t.replace("2025年度", khnf + "年度");
            if (p.getRuns().isEmpty()) { p.createRun().setText(nt); return; }
            p.getRuns().get(0).setText(nt, 0);
            for (int i = p.getRuns().size() - 1; i >= 1; i--) p.removeRun(i);
        };
        for (XWPFParagraph p : doc.getParagraphs()) fix.accept(p);
        for (XWPFTable tb : doc.getTables()) {
            for (XWPFTableRow row : tb.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) fix.accept(p);
                }
            }
        }
        for (XWPFTable tb : doc.getTables()) {
            for (XWPFTableRow row : tb.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFTable it : cell.getTables()) {
                        for (XWPFTableRow r2 : it.getRows()) {
                            for (XWPFTableCell c2 : r2.getTableCells()) {
                                for (XWPFParagraph p : c2.getParagraphs()) fix.accept(p);
                            }
                        }
                    }
                }
            }
        }
    }

    /** fieldsJson → label→val 映射（本 人 总 结 键直接用字段 label） */
    private Map<String, String> fieldValues(FormRecord record) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> fields = om.readValue(record.getFieldsJson(), List.class);
            for (Map<String, Object> f : fields) {
                String label = String.valueOf(f.get("label"));
                String val = f.get("val") == null ? "" : String.valueOf(f.get("val")).trim();
                if (!label.isEmpty()) m.put(label, val);
            }
        } catch (Exception e) {
            log.warn("智能填报：解析记录字段失败 recordId={}: {}", record.getId(), e.getMessage());
        }
        return m;
    }

    private List<String> splitParas(String summary) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (summary == null || summary.trim().isEmpty()) return out;
        for (String p : summary.replace("\r\n", "\n").split("\n")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) out.add(summary.trim());
        return out;
    }

    /** 值单元格写入：保留首 run 字体格式，多余 run 移除 */
    private void setCellTextPreserveStyle(XWPFTableCell cell, String text) {
        if (cell == null) return;
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (int i = p.getRuns().size() - 1; i >= 0; i--) p.removeRun(i);
        XWPFRun r = p.createRun();
        applyDonorStyle(cell, r);
        r.setText(text == null ? "" : text);
        // 清掉同单元格多余空段
        for (int i = cell.getParagraphs().size() - 1; i >= 1; i--) {
            cell.removeParagraph(i);
        }
    }

    /** 从单元格既有 run 复制字体族/字号/加粗到目标 run（POI 原生属性，稳定） */
    private void applyDonorStyle(XWPFTableCell cell, XWPFRun target) {
        for (XWPFParagraph p : cell.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                String fam = r.getFontFamily();
                int size = r.getFontSize();
                if ((fam != null && !fam.isEmpty()) || size > 0) {
                    if (fam != null && !fam.isEmpty()) target.setFontFamily(fam);
                    if (size > 0) target.setFontSize(size);
                    target.setBold(r.isBold());
                    return;
                }
            }
        }
        target.setFontFamily("仿宋_GB2312");
        target.setFontSize(12);
    }

    /** 总结格：保留首段（"本 人 总 结"标题），删除其余段落 */
    private void clearKeepFirst(XWPFTableCell cell) {
        List<XWPFParagraph> ps = cell.getParagraphs();
        for (int i = ps.size() - 1; i >= 1; i--) {
            cell.removeParagraph(i);
        }
        if (!ps.isEmpty()) {
            XWPFParagraph first = cell.getParagraphs().get(0);
            // 标题段若含内容文本则清空文本（只留标题）
            // 标题通常在首段；若首段含正文（旧模板），重置
            if (first.getRuns().size() > 0 && first.getText() != null && first.getText().contains("2025")) {
                for (int i = first.getRuns().size() - 1; i >= 0; i--) first.removeRun(i);
            }
        }
    }

    /** 在单元格末尾追加新段（沿用单元格既有 run 样式） */
    private void addParaCopyStyle(XWPFTableCell cell, String text) {
        XWPFParagraph np = cell.addParagraph();
        XWPFRun r = np.createRun();
        applyDonorStyle(cell, r);
        r.setText(text);
    }

    private int findSigParagraphIndex(XWPFTableCell cell) {
        List<XWPFParagraph> ps = cell.getParagraphs();
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).getText() != null && ps.get(i).getText().contains("本人签名")) return i;
        }
        return ps.size();
    }

    /** 签名行样式快照（对齐+字体） */
    private static class SigStyle {
        org.apache.poi.xwpf.usermodel.ParagraphAlignment align;
        String fontFamily;
        int fontSize;
        boolean bold;
    }

    /**
     * 续篇格填充：记录“本人签名：”行样式 → 清空全格 → 先写续篇段落 → 再按原样式补回签名行。
     * 不做 XML 节点移动（moveXml 会使 POI 缓存对象失联）。
     */
    private void fillContCell(XWPFTableCell cell, List<String> paras) {
        SigStyle sig = null;
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (p.getText() != null && p.getText().contains("本人签名")) {
                sig = new SigStyle();
                sig.align = p.getAlignment();
                if (!p.getRuns().isEmpty()) {
                    XWPFRun r = p.getRuns().get(0);
                    sig.fontFamily = r.getFontFamily();
                    sig.fontSize = r.getFontSize();
                    sig.bold = r.isBold();
                }
                break;
            }
        }
        for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
            cell.removeParagraph(i);
        }
        for (String t : paras) {
            XWPFParagraph np = cell.addParagraph();
            XWPFRun r = np.createRun();
            applyDonorStyle(cell, r);
            r.setText(t);
        }
        XWPFParagraph sp = cell.addParagraph();
        if (sig != null && sig.align != null) sp.setAlignment(sig.align);
        sp.setSpacingBefore(200);
        XWPFRun sr = sp.createRun();
        if (sig != null && sig.fontFamily != null && !sig.fontFamily.isEmpty()) sr.setFontFamily(sig.fontFamily);
        if (sig != null && sig.fontSize > 0) sr.setFontSize(sig.fontSize);
        if (sig != null && sig.bold) sr.setBold(true);
        sr.setText("本人签名：");
    }
}
