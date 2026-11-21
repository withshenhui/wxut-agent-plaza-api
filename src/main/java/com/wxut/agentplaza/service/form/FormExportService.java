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

            // 本人总结：表1 行3 大格 + 表2 行0 续篇格
            String summary = vals.getOrDefault("本 人 总 结", "");
            List<String> paras = splitParas(summary);
            int splitAt = Math.max(1, (int) Math.round(paras.size() * SPLIT_RATIO));
            XWPFTableCell sumCell = t1.getRow(3) != null ? t1.getRow(3).getCell(0) : null;
            XWPFTableCell contCell = t2.getRow(0) != null ? t2.getRow(0).getCell(0) : null;
            if (sumCell != null) {
                clearKeepFirst(sumCell);
                for (int i = 0; i < Math.min(splitAt, paras.size()); i++) {
                    addParaCopyStyle(sumCell, paras.get(i));
                }
            }
            if (contCell != null) {
                fillContCell(contCell, paras.subList(Math.min(splitAt, paras.size()), paras.size()));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
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
