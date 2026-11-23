package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.FormRecord;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.FormRecordMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.SysOperationLogService;
import com.wxut.agentplaza.service.form.FormAgentService;
import com.wxut.agentplaza.service.form.FormExportService;
import com.wxut.agentplaza.service.form.FormFillService;
import com.wxut.agentplaza.service.form.FormMemoryService;
import com.wxut.agentplaza.service.form.FormWarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 智能填报：模板库 / 教师检索 / 填报记录 / SSE 填写流水线 / 原模板导出。
 * 普通接口走 JWT 鉴权（SecurityConfig anyRequest 已覆盖）；
 * SSE 用 EventSource 无法带 Authorization 头，token 走 ?token= 查询参数（见 JwtAuthenticationFilter）。
 */
@Tag(name = "智能填报")
@RestController
@RequestMapping("/api/v1/form")
@RequiredArgsConstructor
public class FormAgentController {

    private final FormAgentService agentService;
    private final FormFillService fillService;
    private final FormWarehouseService warehouseService;
    private final FormExportService exportService;
    private final FormMemoryService memoryService;
    private final SysOperationLogService logService;
    private final com.wxut.agentplaza.service.SysConfigService sysConfigService;
    private final FormRecordMapper recordMapper;
    private final SysUserMapper userMapper;

    // ================= 当前用户 =================

    /** 从 Authentication 解析用户名（principal 为 userId） */
    private String usernameOf(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return "";
        try {
            SysUser u = userMapper.selectById((Long) auth.getPrincipal());
            return u == null ? "" : u.getUsername();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    // ================= 模板 =================

    @GetMapping("/templates")
    @Operation(summary = "模板列表（管理员含停用）")
    public Result<List<Map<String, Object>>> listTemplates(Authentication auth,
                                                            @RequestParam(defaultValue = "false") boolean all) {
        return Result.success(agentService.listTemplates(all && isAdmin(auth)));
    }

    @GetMapping("/templates/{id}")
    @Operation(summary = "模板详情")
    public Result<Map<String, Object>> getTemplate(@PathVariable Long id) {
        Map<String, Object> t = agentService.getTemplate(id);
        if (t == null) return Result.error(404, "模板不存在");
        return Result.success(t);
    }

    @PostMapping("/templates/upload")
    @Operation(summary = "上传自定义模板（docx/xlsx/pdf）")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> uploadTemplate(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String name,
                                                      Authentication auth) {
        try {
            return Result.success(agentService.createFromUpload(file, name, usernameOf(auth)));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "模板解析失败：" + e.getMessage());
        }
    }

    @PutMapping("/templates/{id}")
    @Operation(summary = "编辑模板（名称/说明/启停）")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateTemplate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        agentService.updateTemplate(id,
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("description") == null ? null : String.valueOf(body.get("description")),
                status == null ? null : Integer.valueOf(String.valueOf(status)));
        return Result.success();
    }

    @DeleteMapping("/templates/{id}")
    @Operation(summary = "删除模板")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        agentService.deleteTemplate(id);
        return Result.success();
    }

    // ================= 教师检索（数仓） =================

    @GetMapping("/teachers")
    @Operation(summary = "按姓名/工号检索教师（数据仓）")
    public Result<List<Map<String, Object>>> searchTeachers(@RequestParam(defaultValue = "") String keyword) {
        return Result.success(warehouseService.searchTeachers(keyword == null ? "" : keyword.trim()));
    }

    // ================= 填报记录 =================

    @GetMapping("/records")
    @Operation(summary = "填报记录分页（仅本人，管理员亦只看自己的）")
    public Result<PageResult<Map<String, Object>>> pageRecords(Authentication auth,
                                                                @RequestParam(defaultValue = "1") long page,
                                                                @RequestParam(defaultValue = "10") long size,
                                                                @RequestParam(required = false) String keyword,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String tplName) {
        return Result.success(agentService.pageRecords(page, size, keyword, status, tplName,
                usernameOf(auth), false));
    }

    @GetMapping("/records/{id}")
    @Operation(summary = "记录详情（含字段，本人或管理员）")
    public Result<Map<String, Object>> getRecord(@PathVariable Long id, Authentication auth) {
        FormRecord fr = recordMapper.selectById(id);
        if (fr == null) return Result.error(404, "记录不存在");
        if (!isAdmin(auth) && !usernameOf(auth).equals(fr.getCreatedBy())) {
            return Result.error(403, "仅可查看本人的填报记录");
        }
        Map<String, Object> r = agentService.getRecord(id);
        if (r == null) return Result.error(404, "记录不存在");
        return Result.success(r);
    }

    /** 记录归属校验：本人或管理员 */
    private void assertOwner(FormRecord r, Authentication auth) {
        if (r == null) throw new IllegalArgumentException("记录不存在");
        if (isAdmin(auth)) return;
        if (!usernameOf(auth).equals(r.getCreatedBy())) throw new IllegalArgumentException("无权操作他人记录");
    }

    // ================= 填报记忆（User Memory：智能预填数据源） =================

    @GetMapping("/memory")
    @Operation(summary = "本人填报记忆（可按模板过滤，供智能预填）")
    public Result<List<Map<String, Object>>> memory(Authentication auth,
                                                    @RequestParam(required = false) Long templateId) {
        return Result.success(memoryService.list(usernameOf(auth), templateId));
    }

    @DeleteMapping("/memory")
    @Operation(summary = "清空本人填报记忆")
    public Result<Void> clearMemory(Authentication auth) {
        memoryService.clear(usernameOf(auth));
        logService.save(opLog(usernameOf(auth), "配置", "智能填报", "清空填报记忆"));
        return Result.success();
    }

    @PostMapping("/ai-material")
    @Operation(summary = "素材 AI 填写（按维度结合数仓事实起草要点；仅登录本人）")
    public Result<Map<String, Object>> aiMaterial(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            SysUser u = null;
            try {
                u = userMapper.selectById((Long) auth.getPrincipal());
            } catch (Exception ignore) {
            }
            if (u == null) return Result.error(401, "请先登录");
            String key = body.get("fieldKey") == null ? "" : String.valueOf(body.get("fieldKey"));
            String label = body.get("fieldLabel") == null ? "" : String.valueOf(body.get("fieldLabel"));
            String current = body.get("current") == null ? "" : String.valueOf(body.get("current"));
            Long tplId = null;
            try {
                if (body.get("templateId") != null) tplId = Long.valueOf(String.valueOf(body.get("templateId")));
            } catch (Exception ignore) {
            }
            // 记忆增强：把该用户该字段上次填写的内容作为起草参考
            String memoryHistory = memoryService.valueOf(u.getUsername(), tplId, key);
            String text = fillService.aiMaterial(u.getUsername(),
                    u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname(),
                    key, label, current, memoryHistory);
            logService.save(opLog(u.getUsername(), "使用", "智能填报",
                    "素材AI填写 · " + label));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("text", text);
            return Result.success(out);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /** 文档解析服务（校园一体机）健康探测：供管理后台展示在线状态 */
    @GetMapping("/parse-service/health")
    public Result<Map<String, Object>> parseHealth() {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        String url = "";
        try {
            String v = sysConfigService.getByKey("pdf.parse_url").getConfigValue();
            url = v == null ? "" : v.trim();
        } catch (Exception ignore) {}
        out.put("url", url);
        if (url.isEmpty()) {
            out.put("online", false);
            out.put("note", "未配置解析服务地址");
            return Result.success(out);
        }
        java.net.HttpURLConnection conn = null;
        boolean online = false;
        try {
            String health = url.replaceAll("/parse/?$", "") + "/health";
            conn = (java.net.HttpURLConnection) new java.net.URL(health).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            online = conn.getResponseCode() == 200;
        } catch (Exception e) {
            online = false;
        } finally {
            if (conn != null) conn.disconnect();
        }
        out.put("online", online);
        return Result.success(out);
    }

    @PostMapping("/records/{id}/parse-upload")
    @Operation(summary = "上传自定义文件解析回填（pdf/xlsx/docx）")
    public Result<Map<String, Object>> parseUpload(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file,
                                                   Authentication auth) {
        try {
            return Result.success(agentService.parseAndFill(id, file, usernameOf(auth), isAdmin(auth)));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "解析失败：" + e.getMessage());
        }
    }

    @PostMapping("/records")
    @Operation(summary = "新建填报记录（进入 AI 填写；填报主体=登录者本人）")
    public Result<Long> createRecord(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) body.get("fields");
            SysUser u = null;
            try {
                u = userMapper.selectById((Long) auth.getPrincipal());
            } catch (Exception ignore) {
            }
            if (u == null) return Result.error(401, "请先登录");
            Long tplId = Long.valueOf(String.valueOf(body.get("templateId")));
            Long id = agentService.createRecord(
                    body.get("name") == null ? null : String.valueOf(body.get("name")),
                    tplId,
                    u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname(),
                    u.getUsername(), // 工号=登录账号，仅可为本人填报
                    fields, u.getUsername());
            memoryService.remember(u.getUsername(), tplId, fields);
            logService.save(opLog(u.getUsername(), "使用", "智能填报",
                    "新建填报记录（本人）· 模板ID " + body.get("templateId")));
            return Result.success(id);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PutMapping("/records/{id}")
    @Operation(summary = "保存记录（预览页编辑落库）")
    public Result<Void> updateRecord(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                     Authentication auth) {
        try {
            agentService.updateRecord(id, body, usernameOf(auth), isAdmin(auth));
            // 记忆落库：把本记录各字段内容沉淀到记录所有者名下（供下次智能预填）
            if (body.get("fields") instanceof List) {
                FormRecord rec = recordMapper.selectById(id);
                if (rec != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fs = (List<Map<String, Object>>) body.get("fields");
                    memoryService.remember(rec.getCreatedBy(), rec.getTemplateId(), fs);
                }
            }
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(403, e.getMessage());
        }
    }

    @DeleteMapping("/records/{id}")
    @Operation(summary = "删除记录")
    public Result<Void> deleteRecord(@PathVariable Long id, Authentication auth) {
        try {
            agentService.deleteRecord(id, usernameOf(auth), isAdmin(auth));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(403, e.getMessage());
        }
    }

    // ================= SSE 填写流水线 =================

    @GetMapping("/records/{id}/stream")
    @Operation(summary = "AI 填写流水线（SSE）")
    public SseEmitter stream(@PathVariable Long id) {
        return fillService.stream(id);
    }

    @GetMapping("/records/{id}/regen")
    @Operation(summary = "单字段重新起草（SSE）")
    public SseEmitter regen(@PathVariable Long id, @RequestParam int index) {
        return fillService.regen(id, index);
    }

    // ================= 原模板导出 =================

    @GetMapping("/records/{id}/export")
    @Operation(summary = "导出原版式 Word（POI 原模板填充）")
    public void export(@PathVariable Long id, Authentication auth, HttpServletResponse response) throws Exception {
        FormRecord r = recordMapper.selectById(id);
        assertOwner(r, auth);
        byte[] docx = exportService.export(r);
        if (docx == null) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(400);
            response.getWriter().write("{\"code\":400,\"msg\":\"该模板未内置原版式文件，暂不支持导出 Word\"}");
            return;
        }
        String fname = r.getName() == null ? "智能填报导出" : r.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(fname + ".docx", "UTF-8");
        } catch (Exception e) {
            encoded = "export.docx";
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setContentLength(docx.length);
        response.getOutputStream().write(docx);
        response.getOutputStream().flush();
        logService.save(opLog(usernameOf(auth), "导出", "智能填报",
                "导出「" + r.getTemplateName() + "」记录 #" + r.getId() + " 原版式 Word"));
    }

    /** 操作日志构造（不落 IP 细节，保持轻量） */
    private SysOperationLog opLog(String username, String operation, String target, String detail) {
        SysOperationLog l = new SysOperationLog();
        l.setUsername(username);
        l.setOperation(operation + " · " + target);
        l.setMethod("FORM");
        l.setParams(detail);
        l.setCreateTime(java.time.LocalDateTime.now());
        return l;
    }
}
