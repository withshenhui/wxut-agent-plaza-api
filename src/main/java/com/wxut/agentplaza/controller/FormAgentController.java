package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.form.FormAgentService;
import com.wxut.agentplaza.service.form.FormFillService;
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

import java.util.List;
import java.util.Map;

/**
 * 智能填报：模板库 / 教师检索 / 填报记录 / SSE 填写流水线。
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
    @Operation(summary = "填报记录分页（普通用户仅本人）")
    public Result<PageResult<Map<String, Object>>> pageRecords(Authentication auth,
                                                                @RequestParam(defaultValue = "1") long page,
                                                                @RequestParam(defaultValue = "10") long size,
                                                                @RequestParam(required = false) String keyword,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String tplName) {
        return Result.success(agentService.pageRecords(page, size, keyword, status, tplName,
                usernameOf(auth), isAdmin(auth)));
    }

    @GetMapping("/records/{id}")
    @Operation(summary = "记录详情（含字段）")
    public Result<Map<String, Object>> getRecord(@PathVariable Long id) {
        Map<String, Object> r = agentService.getRecord(id);
        if (r == null) return Result.error(404, "记录不存在");
        return Result.success(r);
    }

    @PostMapping("/records")
    @Operation(summary = "新建填报记录（进入 AI 填写）")
    public Result<Long> createRecord(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) body.get("fields");
            Long id = agentService.createRecord(
                    body.get("name") == null ? null : String.valueOf(body.get("name")),
                    Long.valueOf(String.valueOf(body.get("templateId"))),
                    body.get("teacherName") == null ? "" : String.valueOf(body.get("teacherName")),
                    body.get("teacherId") == null ? "" : String.valueOf(body.get("teacherId")),
                    fields, usernameOf(auth));
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
}
