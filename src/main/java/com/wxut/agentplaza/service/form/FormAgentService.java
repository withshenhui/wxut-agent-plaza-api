package com.wxut.agentplaza.service.form;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.entity.FormRecord;
import com.wxut.agentplaza.entity.FormTemplate;
import com.wxut.agentplaza.mapper.FormRecordMapper;
import com.wxut.agentplaza.mapper.FormTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能填报-模板与记录管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormAgentService {

    private final FormTemplateMapper templateMapper;
    private final FormRecordMapper recordMapper;
    private final FormParseService parseService;
    private final ObjectMapper om = new ObjectMapper();

    /** 上传模板文件的存储目录（预置实模板走 classpath:form-templates/） */
    public static final String TEMPLATE_DIR = "/app/aicenter-form-templates";

    /** 启动时种子预置模板（表为空才写入，不覆盖已有） */
    public void ensureSeed() {
        try {
            Long cnt = templateMapper.selectCount(null);
            if (cnt == null || cnt == 0) {
                for (Object[] p : FormFieldCatalog.presets()) {
                    templateMapper.insert(toTemplate(p));
                }
                log.info("智能填报：已种子 {} 套预置模板", FormFieldCatalog.presets().size());
            }
            upgradeKhglPreset();
        } catch (Exception e) {
            log.error("智能填报：预置模板种子失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    private FormTemplate toTemplate(Object[] p) throws com.fasterxml.jackson.core.JsonProcessingException {
        FormTemplate t = new FormTemplate();
        t.setName((String) p[0]);
        t.setDescription(((String) p[1]) + "。 " + p[2]);
        t.setSource("preset");
        t.setFileType("");
        t.setFileName("");
        if (p.length >= 7) { // 实模板：fileType/fileName/filePath
            t.setFileType((String) p[4]);
            t.setFileName((String) p[5]);
            t.setFilePath((String) p[6]);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) p[3];
        t.setFieldsJson(om.writeValueAsString(fields));
        t.setFieldCount(fields.size());
        t.setStatus(1);
        t.setCreatedBy("system");
        return t;
    }

    /**
     * 预置“年度考核登记表”升级为省级实模板版（幂等）：
     * 旧版 fields_json 不含 summary1，命中即覆盖字段定义与文件信息。
     */
    /** 预置「年度考核登记表」幂等同步：description/字段schema/模板文件与代码不一致即覆盖（代码即真理；仅动 source=preset） */
    private void upgradeKhglPreset() {
        try {
            List<FormTemplate> all = templateMapper.selectList(null);
            for (FormTemplate t : all) {
                if (!"preset".equals(t.getSource())) continue;
                boolean isKh = t.getName() != null && t.getName().contains("年度考核登记表");
                if (!isKh) continue;
                for (Object[] p : FormFieldCatalog.presets()) {
                    if ("年度考核登记表".equals(p[0])) {
                        FormTemplate fresh = toTemplate(p);
                        boolean same = fresh.getFieldsJson().equals(t.getFieldsJson())
                                && fresh.getDescription().equals(t.getDescription())
                                && String.valueOf(fresh.getFilePath()).equals(String.valueOf(t.getFilePath()));
                        if (!same) {
                            t.setDescription(fresh.getDescription());
                            t.setFileType(fresh.getFileType());
                            t.setFileName(fresh.getFileName());
                            t.setFilePath(fresh.getFilePath());
                            t.setFieldsJson(fresh.getFieldsJson());
                            t.setFieldCount(fresh.getFieldCount());
                            templateMapper.updateById(t);
                            log.info("智能填报：已同步「年度考核登记表」预置模板（字段/文案与代码对齐）");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("智能填报：考核表模板升级失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listTemplates(boolean all, String username, boolean admin) {
        LambdaQueryWrapper<FormTemplate> q = new LambdaQueryWrapper<FormTemplate>()
                .eq(!all, FormTemplate::getStatus, 1)
                .orderByDesc(FormTemplate::getSource)
                .orderByAsc(FormTemplate::getId);
        // 非管理员：只见内置预设模板 + 本人上传的自定义模板
        if (!admin && username != null && !username.isEmpty()) {
            q.and(w -> w.eq(FormTemplate::getSource, "preset").or().eq(FormTemplate::getCreatedBy, username));
        }
        List<FormTemplate> list = templateMapper.selectList(q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FormTemplate t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("description", t.getDescription() == null ? "" : t.getDescription());
            m.put("source", t.getSource());
            m.put("fileType", t.getFileType() == null ? "" : t.getFileType());
            m.put("fileName", t.getFileName() == null ? "" : t.getFileName());
            m.put("fieldCount", t.getFieldCount());
            m.put("status", t.getStatus());
            m.put("fields", parseFields(t));
            m.put("hasFile", t.getFilePath() != null && !t.getFilePath().isEmpty());
            m.put("createdAt", fmt(t.getCreatedAt()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> getTemplate(Long id) {
        FormTemplate t = templateMapper.selectById(id);
        if (t == null) return null;
        for (Map<String, Object> m : listTemplates(true, null, true)) {
            if (m.get("id").equals(id)) return m;
        }
        return null;
    }

    /** 导出用：取模板实体（含 file_path） */
    public FormTemplate getTemplateEntity(Long id) {
        return id == null ? null : templateMapper.selectById(id);
    }

    /** 上传自定义模板：解析 → 字段映射 → 入库（文件持久化到磁盘，供后续导出） */
    public Map<String, Object> createFromUpload(MultipartFile file, String name, String createdBy) throws Exception {
        String fileName = file.getOriginalFilename() == null ? "自定义模板" : file.getOriginalFilename();
        byte[] bytes = readAll(file);
        List<String> labels = parseService.extractLabels(bytes, fileName);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("未在模板中识别出可填写字段，请确认文件为包含表格/栏目的 docx、xlsx 或 pdf");
        }
        List<Map<String, Object>> fields = parseService.mapFields(labels);
        FormTemplate t = new FormTemplate();
        t.setName(name == null || name.trim().isEmpty() ? fileName.replaceAll("\\.[^.]+$", "") : name.trim());
        t.setDescription("自定义模板 · " + fileName + " · 已建立字段映射");
        t.setSource("custom");
        t.setFileType(fileName.replaceAll("^.*\\.", "").toLowerCase());
        t.setFileName(fileName);
        t.setFieldsJson(om.writeValueAsString(fields));
        t.setFieldCount(fields.size());
        t.setStatus(1);
        t.setCreatedBy(createdBy);
        if ("docx".equalsIgnoreCase(t.getFileType())) {
            String path = saveTemplateFile(fileName, bytes);
            t.setFilePath(path);
        }
        templateMapper.insert(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", t.getName());
        out.put("fieldCount", t.getFieldCount());
        int db = 0, ai = 0;
        for (Map<String, Object> f : fields) {
            if ("db".equals(f.get("src"))) db++;
            if ("ai".equals(f.get("src"))) ai++;
        }
        out.put("dbCount", db);
        out.put("aiCount", ai);
        out.put("needCount", fields.size() - db - ai);
        return out;
    }

    /** 取模板原文件字节：磁盘优先，classpath: 前缀走 war 内预置资源；找不到返回 null */
    public byte[] loadTemplateBytes(FormTemplate t) {
        if (t == null || t.getFilePath() == null || t.getFilePath().isEmpty()) return null;
        try {
            if (t.getFilePath().startsWith("classpath:")) {
                String res = t.getFilePath().substring("classpath:".length());
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
                    if (in == null) return null;
                    return readAll(in);
                }
            }
            java.io.File f = new java.io.File(t.getFilePath());
            if (!f.exists()) return null;
            try (InputStream in = new java.io.FileInputStream(f)) {
                return readAll(in);
            }
        } catch (Exception e) {
            log.warn("智能填报：读取模板文件失败 {}: {}", t.getFilePath(), e.getMessage());
            return null;
        }
    }

    /** 上传模板文件落盘，返回绝对路径 */
    private String saveTemplateFile(String fileName, byte[] bytes) throws Exception {
        java.io.File dir = new java.io.File(TEMPLATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("模板目录创建失败：" + TEMPLATE_DIR);
        String safe = System.currentTimeMillis() + "-" + fileName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        java.io.File out = new java.io.File(dir, safe);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(bytes);
        }
        return out.getAbsolutePath();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    public void updateTemplate(Long id, String name, String description, Integer status) {
        FormTemplate t = templateMapper.selectById(id);
        if (t == null) throw new IllegalArgumentException("模板不存在");
        if (name != null && !name.trim().isEmpty()) t.setName(name.trim());
        if (description != null) t.setDescription(description);
        if (status != null) t.setStatus(status);
        templateMapper.updateById(t);
    }

    public void deleteTemplate(Long id) {
        templateMapper.deleteById(id);
    }

    // ================= 记录 =================

    public PageResult<Map<String, Object>> pageRecords(long page, long size, String keyword,
                                                        String status, String tplName,
                                                        String currentUser, boolean admin) {
        LambdaQueryWrapper<FormRecord> q = new LambdaQueryWrapper<FormRecord>()
                .eq(status != null && !status.isEmpty(), FormRecord::getStatus, status)
                .eq(tplName != null && !tplName.isEmpty(), FormRecord::getTemplateName, tplName)
                .and(!admin, w -> w.eq(FormRecord::getCreatedBy, currentUser))
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(FormRecord::getName, keyword)
                        .or().like(FormRecord::getTeacherName, keyword))
                .orderByDesc(FormRecord::getId);
        Page<FormRecord> p = recordMapper.selectPage(new Page<>(page, size), q);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FormRecord r : p.getRecords()) {
            rows.add(recordBrief(r));
        }
        PageResult<Map<String, Object>> out = new PageResult<>();
        out.setTotal(p.getTotal());
        out.setRecords(rows);
        return out;
    }

    private Map<String, Object> recordBrief(FormRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("templateId", r.getTemplateId());
        m.put("templateName", r.getTemplateName());
        m.put("teacherName", r.getTeacherName());
        m.put("teacherId", r.getTeacherId());
        m.put("fillCount", r.getFillCount());
        m.put("totalCount", r.getTotalCount());
        m.put("status", r.getStatus());
        m.put("exportFormats", r.getExportFormats() == null ? "" : r.getExportFormats());
        m.put("createdAt", fmt(r.getCreatedAt()));
        m.put("updatedAt", fmt(r.getUpdatedAt()));
        return m;
    }

    public Map<String, Object> getRecord(Long id) {
        FormRecord r = recordMapper.selectById(id);
        if (r == null) return null;
        Map<String, Object> m = recordBrief(r);
        m.put("fields", parseFields(r.getFieldsJson()));
        m.put("createdBy", r.getCreatedBy());
        return m;
    }

    public Long createRecord(String name, Long templateId, String teacherName, String teacherId,
                             List<Map<String, Object>> fields, String username) {
        FormTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new IllegalArgumentException("模板不存在");
        FormRecord r = new FormRecord();
        r.setName(name == null || name.trim().isEmpty()
                ? t.getName() + " · " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now())
                : name.trim());
        r.setTemplateId(templateId);
        r.setTemplateName(t.getName());
        r.setTeacherName(teacherName == null ? "" : teacherName);
        r.setTeacherId(teacherId == null ? "" : teacherId);
        List<Map<String, Object>> fs = fields != null ? fields : parseFields(t);
        // 考核年度：隐藏字段，由流水线大模型分析生成（导出与预览共用）
        Map<String, Object> khnf = new LinkedHashMap<>();
        khnf.put("g", "g0"); khnf.put("label", "考核年度");
        khnf.put("src", "calc"); khnf.put("key", "_khnf"); khnf.put("val", "");
        fs.add(khnf);
        r.setFieldsJson(writeFields(fs));
        r.setTotalCount(fs.size() - 1);
        r.setFillCount(countFilled(fs));
        r.setStatus(statusOf(fs));
        r.setExportFormats("");
        r.setCreatedBy(username);
        recordMapper.insert(r);
        return r.getId();
    }

    public void updateRecord(Long id, Map<String, Object> body, String username, boolean admin) {
        FormRecord r = recordMapper.selectById(id);
        if (r == null) throw new IllegalArgumentException("记录不存在");
        if (!admin && !username.equals(r.getCreatedBy())) throw new IllegalArgumentException("无权修改他人记录");
        if (body.containsKey("name")) r.setName(String.valueOf(body.get("name")));
        if (body.containsKey("exportFormats")) r.setExportFormats(String.valueOf(body.get("exportFormats")));
        Object fields = body.get("fields");
        if (fields instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fs = (List<Map<String, Object>>) fields;
            r.setFieldsJson(writeFields(fs));
            r.setTotalCount((int) fs.stream().filter(f -> !isHidden(f)).count());
            r.setFillCount(countFilled(fs));
            // 显式 status 优先（点「完成」即完成，不被 need 字段空值否决）；未带则按填写完整度推导
            r.setStatus(body.containsKey("status") ? String.valueOf(body.get("status")) : statusOf(fs));
        } else if (body.containsKey("status")) {
            r.setStatus(String.valueOf(body.get("status")));
        }
        r.setUpdatedAt(LocalDateTime.now());
        recordMapper.updateById(r);
    }

    public void deleteRecord(Long id, String username, boolean admin) {
        FormRecord r = recordMapper.selectById(id);
        if (r == null) return;
        if (!admin && !username.equals(r.getCreatedBy())) throw new IllegalArgumentException("无权删除他人记录");
        recordMapper.deleteById(id);
    }

    // ================= 工具 =================

    private List<Map<String, Object>> parseFields(FormTemplate t) {
        return parseFields(t.getFieldsJson());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFields(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return om.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeFields(List<Map<String, Object>> fields) {
        try {
            return om.writeValueAsString(fields);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 自定义文件解析回填：抽取文档文本 → LLM 依据文本抽取字段值 → 仅回填空字段（不动已核对内容）。
     * 支持 pdf / xlsx / docx；pdf 优先一体机解析服务，失败回退本地 PDFBox。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseAndFill(Long recordId, org.springframework.web.multipart.MultipartFile file,
                                            String username, boolean admin) throws Exception {
        FormRecord r = recordMapper.selectById(recordId);
        if (r == null) throw new IllegalArgumentException("填报记录不存在");
        if (!admin && !username.equals(r.getCreatedBy())) throw new IllegalArgumentException("无权修改他人记录");
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".xlsx") && !lower.endsWith(".docx")) {
            throw new IllegalArgumentException("仅支持 PDF / XLSX / DOCX 文件");
        }
        if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("单个文件不超过 20MB");
        byte[] bytes = file.getBytes();
        String text = parseService.extractText(bytes, name);
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("未能解析出文本内容（可能是扫描版 PDF，请改用可选中文本的文件）");
        }
        List<Map<String, Object>> fields = om.readValue(r.getFieldsJson(), List.class);
        Map<String, String> vals = parseService.extractValues(text, fields);
        List<Map<String, Object>> filled = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            Object labelObj = f.get("label");
            String label = labelObj == null ? "" : String.valueOf(labelObj);
            String v = vals.get(label);
            if (v == null || v.trim().isEmpty()) {
                // 归一化匹配：LLM 返回键可能省略括注/增删字词（如 "论文数" vs "论文数（合计）"）
                for (Map.Entry<String, String> en : vals.entrySet()) {
                    String k = en.getKey();
                    if (k == null || k.trim().isEmpty() || en.getValue() == null || en.getValue().trim().isEmpty()) continue;
                    if (k.equals(label) || k.contains(label) || label.contains(k)) { v = en.getValue(); break; }
                }
            }
            if (v == null || v.trim().isEmpty()) continue;
            String cur = f.get("val") == null ? "" : String.valueOf(f.get("val"));
            if (cur.trim().isEmpty()) {
                f.put("val", v);
                f.put("upFrom", "upload");
                f.put("edited", true);
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("label", label);
                it.put("val", v);
                filled.add(it);
            } else if (!cur.trim().equals(v.trim())) {
                skipped.add(label);
            }
        }
        r.setFieldsJson(om.writeValueAsString(fields));
        r.setFillCount(countFilled(fields));
        r.setStatus(statusOf(fields));
        r.setUpdatedAt(LocalDateTime.now());
        recordMapper.updateById(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filled", filled);
        out.put("count", filled.size());
        out.put("skipped", skipped);
        out.put("textChars", text.length());
        return out;
    }

    private static int countFilled(List<Map<String, Object>> fields) {
        int n = 0;
        for (Map<String, Object> f : fields) {
            if (isHidden(f)) continue;
            String val = str(f.get("val"));
            if ("need".equals(f.get("src")) && val.isEmpty()) continue;
            if (val.isEmpty() && !"need".equals(f.get("src"))) continue;
            if (!val.isEmpty()) n++;
        }
        return n;
    }

    /** 隐藏系统字段（_ 前缀，如考核年度），不参与填写进度统计 */
    static boolean isHidden(Map<String, Object> f) {
        return String.valueOf(f.get("key")).startsWith("_");
    }

    private static String statusOf(List<Map<String, Object>> fields) {
        for (Map<String, Object> f : fields) {
            if ("need".equals(f.get("src")) && str(f.get("val")).isEmpty()) return "todo";
        }
        return "done";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(t);
    }

    private static byte[] readAll(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
