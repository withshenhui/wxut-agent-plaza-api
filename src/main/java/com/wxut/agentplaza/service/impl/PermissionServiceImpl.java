package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.SysCollege;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.SysUserPerm;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.mapper.SysUserPermMapper;
import com.wxut.agentplaza.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysUserMapper userMapper;
    private final SysUserPermMapper permMapper;
    private final SysCollegeMapper collegeMapper;

    /** 进程内缓存（量小；权限变更走 evict） */
    private final Map<Long, Set<String>> cache = new ConcurrentHashMap<>();

    public static final Set<String> USER_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "teacher.form", "kb.use", "dev.entry"
    )));

    /** 辅导员（t_xg_fdy 命中）自动加授 */
    public static final Set<String> FDY_GRANTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "insight.funding", "insight.safety", "insight.academic", "insight.mental", "insight.export"
    )));

    public static final Set<String> COLLEGE_ADMIN_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "teacher.form", "kb.use", "dev.entry",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental", "insight.export",
            "admin.dashboard"
    )));

    public static final Set<String> LEADER_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "chat.all",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental", "insight.export",
            "teacher.form", "kb.use", "dev.entry"
    )));

    public static final Set<String> ADMIN_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "chat.all",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental", "insight.export",
            "teacher.form", "kb.use", "dev.entry",
            "admin.dashboard", "admin.agent", "admin.model", "admin.user", "admin.config", "admin.file", "admin.log", "admin.xixi"
    )));

    @Override
    public Set<String> perms(Long userId) {
        return cache.computeIfAbsent(userId, this::compute);
    }

    private Set<String> compute(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        String role = user.getRole() == null ? "user" : user.getRole();
        switch (role) {
            case "admin": out.addAll(ADMIN_DEFAULT); break;
            case "leader": out.addAll(LEADER_DEFAULT); break;
            case "college_admin": out.addAll(COLLEGE_ADMIN_DEFAULT); break;
            default: out.addAll(USER_DEFAULT); break;
        }
        // 个人覆盖：加授并集，剥夺差集
        for (SysUserPerm p : permMapper.selectList(
                new LambdaQueryWrapper<SysUserPerm>().eq(SysUserPerm::getUserId, userId))) {
            if (p.getGranted() != null && p.getGranted() == 1) out.add(p.getPermKey());
            else out.remove(p.getPermKey());
        }
        return out;
    }

    @Override
    public boolean has(Long userId, String permKey) {
        return perms(userId).contains(permKey);
    }

    @Override
    public void assertPerm(Long userId, String permKey) {
        if (!has(userId, permKey)) {
            throw new BusinessException(403, "无权使用该功能（" + permKey + "），如需开通请联系管理员");
        }
    }

    @Override
    public Map<String, Object> scope(Long userId) {
        SysUser user = userMapper.selectById(userId);
        Map<String, Object> r = new HashMap<>();
        String role = user == null || user.getRole() == null ? "user" : user.getRole();
        boolean all = "admin".equals(role) || "leader".equals(role)
                // 全校锡锡授权视为全校范围（可单独授 chat.all 给研究人员）
                || has(userId, "chat.all");
        r.put("scope", all ? "all" : "college");
        Integer cid = user == null ? null : user.getCollegeId();
        if (cid == null) cid = -1;
        r.put("collegeId", all ? 0 : cid);
        if (cid >= 0) {
            SysCollege c = collegeMapper.selectById(cid);
            r.put("collegeName", c != null ? c.getName() : null);
        } else {
            r.put("collegeName", null);
        }
        return r;
    }

    @Override
    public String resolveCollegeFilter(Long userId, String requested) {
        Map<String, Object> s = scope(userId);
        if ("all".equals(s.get("scope"))) return requested;
        Integer cid = (Integer) s.get("collegeId");
        if (cid == null || cid < 0) throw new BusinessException(403, "账号未关联学院，无法使用数据洞察功能，请联系管理员");
        SysCollege c = collegeMapper.selectById(cid);
        return c != null ? c.getName() : null;
    }

    @Override
    public void assertCollegeAdmin(Long userId) {
        SysUser user = userMapper.selectById(userId);
        String role = user == null || user.getRole() == null ? "user" : user.getRole();
        if (!"admin".equals(role) && !("college_admin".equals(role) && user.getCollegeId() != null)) {
            throw new BusinessException(403, "仅学院管理员可使用该功能");
        }
    }

    @Override
    public void evict(Long userId) {
        cache.remove(userId);
    }
}
