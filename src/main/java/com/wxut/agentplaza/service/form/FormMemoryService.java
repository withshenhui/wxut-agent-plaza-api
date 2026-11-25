package com.wxut.agentplaza.service.form;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.FormFieldMemory;
import com.wxut.agentplaza.mapper.FormFieldMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户填报记忆服务（User Memory）。
 * 记住用户在每个模板各字段填过的内容，供下次「智能预填」与「AI 起草参考」使用；仅本人可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormMemoryService {

    private final FormFieldMemoryMapper mapper;

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 列出某用户的记忆（可按模板过滤），供前端预填 */
    public List<Map<String, Object>> list(String username, Long templateId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (username == null || username.isEmpty()) return out;
        LambdaQueryWrapper<FormFieldMemory> q = new LambdaQueryWrapper<FormFieldMemory>()
                .eq(FormFieldMemory::getUsername, username)
                .eq(templateId != null, FormFieldMemory::getTemplateId, templateId)
                .orderByDesc(FormFieldMemory::getUpdatedAt);
        for (FormFieldMemory m : mapper.selectList(q)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", m.getFieldKey());
            o.put("label", m.getFieldLabel());
            o.put("value", m.getContent());
            o.put("templateId", m.getTemplateId());
            o.put("useCount", m.getUseCount());
            o.put("updatedAt", m.getUpdatedAt());
            out.add(o);
        }
        return out;
    }

    /** 取某字段的记忆原文（供 AI 起草参考），无则 null */
    public String valueOf(String username, Long templateId, String fieldKey) {
        if (username == null || username.isEmpty() || templateId == null || fieldKey == null || fieldKey.isEmpty()) return null;
        FormFieldMemory m = mapper.selectOne(new LambdaQueryWrapper<FormFieldMemory>()
                .eq(FormFieldMemory::getUsername, username)
                .eq(FormFieldMemory::getTemplateId, templateId)
                .eq(FormFieldMemory::getFieldKey, fieldKey)
                .last("limit 1"));
        return m == null ? null : m.getContent();
    }

    /** 记住用户在本模板各字段的填写内容（仅 material/need 且非空；upsert） */
    public void remember(String username, Long templateId, List<Map<String, Object>> fields) {
        if (username == null || username.isEmpty() || templateId == null || fields == null) return;
        for (Map<String, Object> f : fields) {
            String src = str(f.get("src"));
            if (!"material".equals(src) && !"need".equals(src)) continue;
            String key = str(f.get("key"));
            String label = str(f.get("label"));
            String val = str(f.get("val"));
            if (val.trim().isEmpty()) continue;
            // need 类字段（政治面貌/岗位等级等）key 为空 → 以 label 作稳定标识，否则这些内容永远不会落记忆
            if (key.isEmpty()) {
                if (label.trim().isEmpty()) continue;
                key = "L:" + label;
            }
            try {
                FormFieldMemory exist = mapper.selectOne(new LambdaQueryWrapper<FormFieldMemory>()
                        .eq(FormFieldMemory::getUsername, username)
                        .eq(FormFieldMemory::getTemplateId, templateId)
                        .eq(FormFieldMemory::getFieldKey, key)
                        .last("limit 1"));
                if (exist == null) {
                    FormFieldMemory m = new FormFieldMemory();
                    m.setUsername(username);
                    m.setTemplateId(templateId);
                    m.setFieldKey(key);
                    m.setFieldLabel(label);
                    m.setContent(val);
                    m.setUseCount(1);
                    m.setCreatedAt(LocalDateTime.now());
                    m.setUpdatedAt(LocalDateTime.now());
                    mapper.insert(m);
                } else {
                    exist.setFieldLabel(label);
                    exist.setContent(val);
                    exist.setUseCount((exist.getUseCount() == null ? 0 : exist.getUseCount()) + 1);
                    // 显式设置 updatedAt，避免 MyBatis-Plus 读出的旧值回写覆盖（见 form-fill-save-semantics）
                    exist.setUpdatedAt(LocalDateTime.now());
                    mapper.updateById(exist);
                }
            } catch (Exception e) {
                log.warn("填写记忆写入失败（{} · {}）：{}", username, key, e.getMessage());
            }
        }
    }

    /** 清空某用户的全部记忆 */
    public void clear(String username) {
        if (username == null || username.isEmpty()) return;
        mapper.delete(new LambdaQueryWrapper<FormFieldMemory>().eq(FormFieldMemory::getUsername, username));
    }
}
