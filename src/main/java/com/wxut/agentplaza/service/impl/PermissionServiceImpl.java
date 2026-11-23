package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.SysCollege;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.SysUserPerm;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.mapper.SysUserPermMapper;
import com.wxut.agentplaza.service.FeatureService;
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
    private final FeatureService featureService;

    /** 进程内缓存（量小；权限变更走 evict） */
    private final Map<Long, Set<String>> cache = new ConcurrentHashMap<>();

    /** 辅导员（t_xg_fdy 命中）自动加授 */
    public static final Set<String> FDY_GRANTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "insight.funding", "insight.safety", "insight.academic", "insight.mental", "insight.employment", "insight.export"
    )));

    /** 已知角色；未映射（含 null/空串/历史脏值）一律回退 user，保持旧 switch 的 default 语义（fail-open） */
    private static final Set<String> KNOWN_ROLES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "user", "college_admin", "leader", "admin"
    )));

    @Override
    public Set<String> perms(Long userId) {
        return cache.computeIfAbsent(userId, this::compute);
    }

    private Set<String> compute(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptySet();
        String rawRole = user.getRole();
        String role = KNOWN_ROLES.contains(rawRole) ? rawRole : "user";
        Set<String> out = new HashSet<>(featureService.defaults(role));
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
        Integer cid = user == null ? null : user.getCollegeId();
        if (cid == null) cid = -1;
        // 学院归属为「全校」虚拟行（sys_college.id=0, scope_all=1）→ 数据范围全校
        // 用途：校资助中心等业务岗——数据全校可见，但不带来任何功能权限（chat.all 等仍需单独授予）
        boolean collegeAll = cid == 0
                || (cid > 0 && isScopeAllCollege(cid));
        boolean all = "admin".equals(role) || "leader".equals(role)
                || has(userId, "chat.all")
                || collegeAll;
        // 全校锡锡（agent 111）随数据范围：范围=全校者可用（工作台与锡锡口径一致）
        r.put("chatAll", all);
        r.put("scope", all ? "all" : "college");
        r.put("collegeId", all ? 0 : cid);
        if (cid >= 0) {
            SysCollege c = collegeMapper.selectById(cid);
            r.put("collegeName", c != null ? c.getName() : null);
        } else {
            r.put("collegeName", null);
        }
        return r;
    }

    private boolean isScopeAllCollege(Integer cid) {
        SysCollege c = collegeMapper.selectById(cid);
        return c != null && c.getScopeAll() != null && c.getScopeAll() == 1;
    }

    @Override
    public String resolveCollegeFilter(Long userId, String requested) {
        Map<String, Object> s = scope(userId);
        if ("all".equals(s.get("scope"))) return requested;
        Integer cid = (Integer) s.get("collegeId");
        if (cid == null || cid < 0) throw new BusinessException(403, "账号未关联学院，无法使用数据洞察功能，请联系管理员");
        SysCollege c = collegeMapper.selectById(cid);
        if (c != null && c.getScopeAll() != null && c.getScopeAll() == 1) return null; // scope 分支兜底
        // 机构号（如机关编制）不含学生数据：明确提示而非空表
        if (cid == 99) throw new BusinessException(403, "当前账号归属为「非学院机构」，不含学生数据；如属学院业务岗请联系管理员调整学院归属");
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
        featureService.evict();
    }
}
