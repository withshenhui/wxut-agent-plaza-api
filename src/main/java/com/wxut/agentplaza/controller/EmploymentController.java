package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.employment.EmploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智慧就业：学生端画像/意向/推荐 + 教师端看板。
 * 权限：学生仅可读写本人（student_no 由登录态强制，不接受请求体传入）；
 * 教师端看板需 ADMIN 或洞察权限账号。
 */
@RestController
@RequestMapping("/api/v1/employment")
@RequiredArgsConstructor
public class EmploymentController {

    private final EmploymentService service;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    private SysUser userOf(Authentication auth) {
        return auth == null ? null : userMapper.selectById((Long) auth.getPrincipal());
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    /** 本人画像（学号/工号 由登录态取） */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile(Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        return Result.success(service.profile(u.getUsername()));
    }

    @GetMapping("/intent")
    public Result<Map<String, Object>> getIntent(Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        return Result.success(service.getIntent(u.getUsername()));
    }

    @PostMapping("/intent")
    public Result<Void> saveIntent(@RequestBody Map<String, Object> body, Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        service.saveIntent(u.getUsername(), body);
        return Result.success(null);
    }

    /** 为本人推荐（学号由登录态强制） */
    @PostMapping("/recommend")
    public Result<Object> recommend(Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        return Result.success(service.recommend(u.getUsername()));
    }

    /** 教师端看板聚合：ADMIN 或任一洞察权限账号（辅导员/就业办/学院管理员） */
    @GetMapping("/admin/overview")
    public Result<Map<String, Object>> overview(Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        boolean ok = isAdmin(auth);
        if (!ok) {
            for (String k : new String[]{"insight.employment", "insight.funding", "insight.safety", "insight.academic", "insight.mental"}) {
                if (permissionService.has(u.getId(), k)) { ok = true; break; }
            }
        }
        if (!ok) return Result.error(403, "没有权限访问就业工作台");
        return Result.success(service.overview(null));
    }
}
