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

    /** 启动时种子预置模板（表为空才写入，不覆盖已有） */
    public void ensureSeed() {
        try {
            Long cnt = templateMapper.selectCount(null);
            if (cnt != null && cnt > 0) return;
            for (Object[] p : FormFieldCatalog.presets()) {
                FormTemplate t = new FormTemplate();
                t.setName((String) p[0]);
                t.setDescription(((String) p[1]) + "。 " + p[2]);
                t.setSource("preset");
                t.setFileType("");
                t.setFileName("");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fields = (List<Map<String, Object>>) p[3];
                t.setFieldsJson(om.writeValueAsString(fields));
                t.setFieldCount(fields.size());
                t.setStatus(1);
                t.setCreatedBy("system");
                templateMapper.insert(t);
            }
            log.info("智能填报：已种子 {} 套预置模板", FormFieldCatalog.presets().size());
        } catch (Exception e) {
            log.error("智能填报：预置模板种子失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listTemplates(boolean all) {
        LambdaQueryWrapper<FormTemplate> q = new LambdaQueryWrapper<FormTemplate>()
                .eq(!all, FormTemplate::getStatus, 1)
                .orderByDesc(FormTemplate::getSource)
                .orderByAsc(FormTemplate::getId);
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
            m.put("createdAt", fmt(t.getCreatedAt()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> getTemplate(Long id) {
        FormTemplate t = templateMapper.selectById(id);
        if (t == null) return null;
        for (Map<String, Object> m : listTemplates(true)) {
            if (m.get("id").equals(id)) return m;
        }
        return null;
    }

    /** 上传自定义模板：解析 → 字段映射 → 入库 */
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
        r.setFieldsJson(writeFields(fs));
        r.setTotalCount(fs.size());
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
            r.setTotalCount(fs.size());
            r.setFillCount(countFilled(fs));
            r.setStatus(statusOf(fs));
        } else if (body.containsKey("status")) {
            r.setStatus(String.valueOf(body.get("status")));
        }
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

    private static int countFilled(List<Map<String, Object>> fields) {
        int n = 0;
        for (Map<String, Object> f : fields) {
            String val = str(f.get("val"));
            if ("need".equals(f.get("src")) && val.isEmpty()) continue;
            if (val.isEmpty() && !"need".equals(f.get("src"))) continue;
            if (!val.isEmpty()) n++;
        }
        return n;
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
