package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.V2Analysis;
import com.wxut.agentplaza.mapper.V2AnalysisMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.time.LocalDateTime;

/**
 * 数据洞察 · AI 分析记录（服务端存储，跟登录人走）：
 *  - 前端 LLM 分析完成后保存；打开详情弹窗时回读最近一次分析
 *  - 同一登录人 + 场景 + 学生：保留最近 N 条，弹窗默认显示最新
 */
@Slf4j
@Tag(name = "数据洞察-AI分析记录")
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final V2AnalysisMapper analysisMapper;

    private static final int MAX_PER_KEY = 10; // 同 (人,场景,学生) 保留条数，超出清最旧

    /** 保存一次 AI 分析（登录人+场景+学生） */
    @PostMapping("/save")
    @Operation(summary = "保存AI分析结果")
    public Result<Map<String, Object>> save(Authentication auth, @RequestBody Map<String, Object> body) {
        Long userId = (Long) auth.getPrincipal();
        String scene = str(body.get("scene"));
        String studentId = str(body.get("studentId"));
        String content = str(body.get("content"));
        if (scene.isEmpty() || studentId.isEmpty() || content.isEmpty()) {
            return Result.error("参数不完整：scene/studentId/content 必填");
        }
        try {
            V2Analysis a = new V2Analysis();
            a.setUserId(userId);
            a.setScene(scene);
            a.setStudentId(studentId);
            a.setStudentName(trimTo(str(body.get("studentName")), 64));
            a.setQuestion(trimTo(str(body.get("question")), 500));
            a.setContent(content);
            a.setModel(trimTo(str(body.get("model")), 64));
            a.setCreatedAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            analysisMapper.insert(a);
            trimOld(userId, scene, studentId);
            Map<String, Object> out = new HashMap<>();
            out.put("id", a.getId());
            out.put("updatedAt", a.getUpdatedAt());
            return Result.success(out);
        } catch (Exception e) {
            log.warn("保存AI分析失败：{}", e.getMessage());
            return Result.error("保存失败：" + e.getMessage());
        }
    }

    /** 取最近一次分析（登录人+场景+学生）；无则返回 null */
    @GetMapping("/latest")
    @Operation(summary = "取最近一次AI分析")
    public Result<Map<String, Object>> latest(Authentication auth, @RequestParam String scene,
                                              @RequestParam String studentId) {
        Long userId = (Long) auth.getPrincipal();
        try {
            V2Analysis a = analysisMapper.selectOne(
                    new LambdaQueryWrapper<V2Analysis>()
                            .eq(V2Analysis::getUserId, userId)
                            .eq(V2Analysis::getScene, scene)
                            .eq(V2Analysis::getStudentId, studentId)
                            .orderByDesc(V2Analysis::getUpdatedAt)
                            .last("LIMIT 1"));
            return Result.success(a == null ? null : toMap(a));
        } catch (Exception e) {
            log.warn("读取AI分析失败：{}", e.getMessage());
            return Result.success(null);
        }
    }

    /** 取该登录人最近的分析列表（可跨场景，供"我的分析"页） */
    @GetMapping("/list")
    @Operation(summary = "取登录人的分析记录列表")
    public Result<List<Map<String, Object>>> list(Authentication auth,
                                                  @RequestParam(required = false) String scene,
                                                  @RequestParam(defaultValue = "30") int limit) {
        Long userId = (Long) auth.getPrincipal();
        int sz = Math.min(Math.max(limit, 1), 100);
        try {
            List<V2Analysis> rows = analysisMapper.selectList(
                    new LambdaQueryWrapper<V2Analysis>()
                            .eq(V2Analysis::getUserId, userId)
                            .eq(scene != null && !scene.isEmpty(), V2Analysis::getScene, scene)
                            .orderByDesc(V2Analysis::getUpdatedAt)
                            .last("LIMIT " + sz));
            List<Map<String, Object>> out = new ArrayList<>();
            for (V2Analysis a : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", a.getId());
                m.put("scene", a.getScene());
                m.put("studentId", a.getStudentId());
                m.put("studentName", a.getStudentName());
                m.put("question", a.getQuestion());
                m.put("model", a.getModel());
                m.put("updatedAt", a.getUpdatedAt());
                out.add(m);
            }
            return Result.success(out);
        } catch (Exception e) {
            log.warn("读取分析列表失败：{}", e.getMessage());
            return Result.success(Collections.emptyList());
        }
    }

    /** 删除一条分析记录（仅本人） */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除一条分析记录")
    public Result<Void> delete(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        analysisMapper.delete(new LambdaQueryWrapper<V2Analysis>()
                .eq(V2Analysis::getId, id).eq(V2Analysis::getUserId, userId));
        return Result.success();
    }

    private void trimOld(Long userId, String scene, String studentId) {
        try {
            List<V2Analysis> rows = analysisMapper.selectList(
                    new LambdaQueryWrapper<V2Analysis>()
                            .eq(V2Analysis::getUserId, userId)
                            .eq(V2Analysis::getScene, scene)
                            .eq(V2Analysis::getStudentId, studentId)
                            .orderByDesc(V2Analysis::getUpdatedAt)
                            .last("LIMIT " + (MAX_PER_KEY + 5)));
            if (rows.size() > MAX_PER_KEY) {
                List<Long> delIds = new ArrayList<>();
                for (int i = MAX_PER_KEY; i < rows.size(); i++) delIds.add(rows.get(i).getId());
                analysisMapper.deleteBatchIds(delIds);
            }
        } catch (Exception ignore) { }
    }

    private Map<String, Object> toMap(V2Analysis a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("scene", a.getScene());
        m.put("studentId", a.getStudentId());
        m.put("studentName", a.getStudentName());
        m.put("question", a.getQuestion());
        m.put("content", a.getContent());
        m.put("model", a.getModel());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
