package com.wxut.agentplaza.service.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能填报-自定义模板解析。
 * docx/xlsx 抽取表格单元格与段落中的字段标签，pdf 抽取文本行标签；
 * 标签经 LLM 映射到规范字段目录（db/ai/need），LLM 不可用时用启发式规则兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormParseService {

    private final FormLlmService llm;
    private final SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    /** 一体机 PDF 解析服务（后台可配，默认 245:8016；不可达自动回退本地 PDFBox） */
    private String pdfServiceUrl() {
        try {
            String v = configService.getByKey("pdf.parse_url").getConfigValue();
            return v == null ? "" : v.trim();
        } catch (Exception e) { return ""; }
    }

    /**
     * 抽取文档全文（记录解析回填用）。
     * xlsx=逐行合并单元格；docx=表格行 + 段落；pdf=优先一体机解析服务，回退本地 PDFBox。
     */
    public String extractText(byte[] bytes, String fileName) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            String via = pdfViaAppliance(bytes, fileName);
            if (via != null && !via.trim().isEmpty()) return via;
            try (PDDocument doc = PDDocument.load(bytes)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        if (lower.endsWith(".docx")) {
            StringBuilder sb = new StringBuilder();
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                for (XWPFTable t : doc.getTables()) {
                    for (XWPFTableRow row : t.getRows()) {
                        StringBuilder line = new StringBuilder();
                        for (XWPFTableCell c : row.getTableCells()) {
                            String tx = c.getText() == null ? "" : c.getText().replaceAll("\\s+", " ").trim();
                            if (!tx.isEmpty()) { if (line.length() > 0) line.append(" | "); line.append(tx); }
                        }
                        if (line.length() > 0) sb.append(line).append('\n');
                    }
                }
                doc.getParagraphs().forEach(p -> {
                    String t = p.getText();
                    if (t != null && !t.trim().isEmpty()) sb.append(t.trim()).append('\n');
                });
            }
            return sb.toString();
        }
        if (lower.endsWith(".xlsx")) {
            StringBuilder sb = new StringBuilder();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                int sheets = Math.min(wb.getNumberOfSheets(), 3);
                for (int i = 0; i < sheets; i++) {
                    XSSFSheet sheet = wb.getSheetAt(i);
                    int last = Math.min(sheet.getLastRowNum(), 300);
                    for (int r = sheet.getFirstRowNum(); r <= last; r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        StringBuilder line = new StringBuilder();
                        for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                            String v = cellStr(row.getCell(ci));
                            if (!v.isEmpty()) { if (line.length() > 0) line.append(" | "); line.append(v); }
                        }
                        if (line.length() > 0) sb.append(line).append('\n');
                    }
                }
            }
            return sb.toString();
        }
        throw new IllegalArgumentException("仅支持 .pdf / .xlsx / .docx 文件");
    }

    private static String cellStr(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) return cell.getStringCellValue().trim();
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                double d = cell.getNumericCellValue();
                return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        } catch (Exception ignore) {
        }
        return "";
    }

    /** 调用一体机 PDF 解析服务：POST 原始字节 + X-File-Name 头，响应 {"text": "..."}；失败返回 null */
    private String pdfViaAppliance(byte[] bytes, String fileName) {
        String url = pdfServiceUrl();
        if (url.isEmpty()) return null;
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("X-File-Name",
                    java.net.URLEncoder.encode(fileName == null ? "file.pdf" : fileName, "UTF-8"));
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("一体机 PDF 解析返回 {}，回退本地 PDFBox", code);
                return null;
            }
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            com.fasterxml.jackson.databind.JsonNode j = om.readTree(bos.toByteArray());
            String text = j.path("text").asText("");
            return text.trim().isEmpty() ? null : text;
        } catch (Exception e) {
            log.warn("一体机 PDF 解析不可达（{}），回退本地 PDFBox", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从文档文本抽取目标字段的值（LLM，仅依据文档内容，不编造） */
    public Map<String, String> extractValues(String text, List<Map<String, Object>> fields) throws Exception {
        List<String> want = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            Object label = f.get("label");
            if (label != null && !String.valueOf(label).trim().isEmpty()) want.add(String.valueOf(label));
        }
        if (want.isEmpty() || text == null || text.trim().isEmpty()) return new LinkedHashMap<>();
        String body = text.length() > 6000 ? text.substring(0, 6000) : text;
        String sys = "你是表单信息抽取器。根据给定的文档解析文本，为目标表格的每个字段抽取对应值。"
                + "只依据文档内容，禁止编造推断；文档中没有对应内容的字段不要输出。"
                + "输出严格 JSON 对象：{\"字段名\":\"值\"}，值为原文中的事实（数字只保留数字与单位，日期用原文格式），不要输出任何其他文字。";
        String user = "目标字段：" + om.writeValueAsString(want) + "\n文档文本：\n" + body;
        String out = llm.chat(sys, user);
        String json = out == null ? "" : out.trim();
        int a = json.indexOf('{'), b = json.lastIndexOf('}');
        if (a >= 0 && b > a) json = json.substring(a, b + 1);
        Map<String, String> vals = new LinkedHashMap<>();
        com.fasterxml.jackson.databind.JsonNode node = om.readTree(json);
        java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> en = it.next();
            String v = en.getValue().asText("");
            if (!v.trim().isEmpty()) vals.put(en.getKey(), v.trim());
        }
        return vals;
    }

    /** 从文件字节中抽取候选字段标签（保持出现顺序，去重，上限 80） */
    public List<String> extractLabels(byte[] bytes, String fileName) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        List<String> labels = new ArrayList<>();
        if (lower.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                for (XWPFTable table : doc.getTables()) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            addLabel(labels, cell.getText());
                        }
                    }
                }
                doc.getParagraphs().forEach(p -> addLabel(labels, p.getText()));
            }
        } else if (lower.endsWith(".xlsx")) {
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                int sheets = Math.min(wb.getNumberOfSheets(), 5);
                for (int i = 0; i < sheets; i++) {
                    XSSFSheet sheet = wb.getSheetAt(i);
                    int last = Math.min(sheet.getLastRowNum(), 400);
                    for (int r = sheet.getFirstRowNum(); r <= last; r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        short lastCell = row.getLastCellNum();
                        for (int cIdx = 0; cIdx < lastCell; cIdx++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(cIdx);
                            if (cell == null) continue;
                            String v = "";
                            try {
                                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                    v = cell.getStringCellValue();
                                }
                            } catch (Exception ignore) {
                            }
                            addLabel(labels, v);
                        }
                    }
                }
            }
        } else if (lower.endsWith(".pdf")) {
            try (PDDocument doc = PDDocument.load(bytes)) {
                String text = new PDFTextStripper().getText(doc);
                for (String line : text.split("\\r?\\n")) {
                    addLabel(labels, line);
                }
            }
        } else {
            throw new IllegalArgumentException("仅支持 .docx / .xlsx / .pdf 模板文件");
        }
        return labels;
    }

    /** 候选规则：以冒号结尾或 2-20 字短标签（过滤说明长句与编号噪声） */
    private void addLabel(List<String> out, String raw) {
        if (raw == null) return;
        String t = raw.replaceAll("\\s+", "").trim();
        if (t.isEmpty()) return;
        boolean colon = t.endsWith("：") || t.endsWith(":");
        t = t.replaceAll("[：:]$", "");
        if (t.isEmpty()) return;
        if (colon) {
            if (t.length() >= 1 && t.length() <= 30 && !t.contains("。") && !t.contains("；")) out.add(t);
        } else if (t.length() >= 2 && t.length() <= 14 && !t.matches(".*[。，；、！？].*") && !t.matches("^[0-9].*")) {
            out.add(t);
        }
    }

    /**
     * 把标签映射为规范字段列表（与预置模板 fields_json 同构）。
     * 优先 LLM 映射；失败时启发式匹配目录标签。
     */
    public List<Map<String, Object>> mapFields(List<String> labels) {
        // 目录标签 → 定义
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (FormFieldCatalog.Def d : FormFieldCatalog.full()) {
            catalog.put(d.label, d.toMap());
        }
        // 目录 key 集合（供 LLM 用）
        List<Map<String, String>> cat = new ArrayList<>();
        for (FormFieldCatalog.Def d : FormFieldCatalog.full()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("label", d.label); m.put("src", d.src); m.put("key", d.key);
            cat.add(m);
        }
        Set<String> seen = new HashSet<>();
        List<String> uniq = new ArrayList<>();
        for (String l : labels) if (seen.add(l)) uniq.add(l);
        if (uniq.size() > 80) uniq = uniq.subList(0, 80);

        Map<String, Map<String, String>> mapping = new LinkedHashMap<>(); // 原标签 → {key,src}
        boolean llmOk = false;
        try {
            String sys = "你是高校表格字段映射助手。把用户给出的表格标签映射到规范字段目录："
                    + "能从教师数据仓直填的映射到目录中 src=db 的字段；"
                    + "需要大模型起草的叙述栏位映射到 src=ai 的字段；"
                    + "目录里没有对应项的（联系方式、家庭信息、主观栏位等）标记 src=need、key 留空。"
                    + "只输出 JSON 数组，每项 {\"label\":\"原标签\",\"key\":\"目录key或空串\",\"src\":\"db|ai|need\"}，不要输出其他文字。";
            String user;
            try {
                user = "规范字段目录：" + om.writeValueAsString(cat) + "\n待映射标签：" + om.writeValueAsString(uniq);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            String out = llm.chat(sys, user);
            String json = out.trim();
            int s = json.indexOf('['), e = json.lastIndexOf(']');
            if (s >= 0 && e > s) json = json.substring(s, e + 1);
            List<Map<String, String>> arr = om.readValue(json,
                    om.getTypeFactory().constructCollectionType(List.class, Map.class));
            for (Map<String, String> m : arr) {
                String l = m.get("label");
                if (l != null) mapping.put(l, m);
            }
            llmOk = !mapping.isEmpty();
        } catch (Exception ex) {
            log.warn("智能填报：LLM 字段映射失败，回落启发式规则：{}", ex.getMessage());
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        int dbN = 0, aiN = 0;
        for (String label : uniq) {
            Map<String, Object> f = null;
            Map<String, String> m = mapping.get(label);
            if (m != null) {
                String key = m.getOrDefault("key", "");
                String src = m.getOrDefault("src", "need");
                if ("db".equals(src) || "ai".equals(src)) {
                    for (Map<String, Object> def : catalog.values()) {
                        if (key.equals(def.get("key")) && src.equals(def.get("src"))) {
                            Map<String, Object> nf = new LinkedHashMap<>(def);
                            nf.put("label", label);
                            f = nf;
                            break;
                        }
                    }
                }
            }
            if (f == null) {
                // 启发式：目录精确/包含匹配
                f = catalog.get(label);
                if (f == null) {
                    for (Map.Entry<String, Map<String, Object>> en : catalog.entrySet()) {
                        if (label.contains(en.getKey()) || en.getKey().contains(label)) {
                            f = en.getValue();
                            break;
                        }
                    }
                }
            }
            if (f == null) {
                Map<String, Object> nf = new LinkedHashMap<>();
                nf.put("g", guessGroup(label));
                nf.put("label", label);
                nf.put("src", "need");
                nf.put("key", "");
                nf.put("conf", 0.0);
                nf.put("basis", "");
                nf.put("hint", "数仓暂无对应字段，请手动填写");
                f = nf;
            } else if ("need".equals(f.get("src")) && f.get("key") == null) {
                f = new LinkedHashMap<>(f);
            } else {
                f = new LinkedHashMap<>(f);
                f.put("label", label);
            }
            if ("db".equals(f.get("src"))) dbN++;
            if ("ai".equals(f.get("src"))) aiN++;
            fields.add(f);
        }
        log.info("智能填报：模板解析 {} 项字段（数仓直填 {} / AI 起草 {} / 需补充 {}），LLM 映射={}",
                fields.size(), dbN, aiN, fields.size() - dbN - aiN, llmOk ? "成功" : "兜底");
        return fields;
    }

    private static String guessGroup(String label) {
        if (label.contains("论文") || label.contains("专利") || label.contains("项目") || label.contains("经费")) return "g3";
        if (label.contains("评教") || label.contains("评价") || label.contains("教学") || label.contains("课时")) return "g4";
        if (label.contains("考核") || label.contains("荣誉") || label.contains("奖")) return "g5";
        if (label.contains("学历") || label.contains("学位") || label.contains("毕业") || label.contains("专业")) return "g2";
        if (label.contains("述职") || label.contains("规划") || label.contains("理由") || label.contains("总结")) return "g6";
        return "g1";
    }
}
