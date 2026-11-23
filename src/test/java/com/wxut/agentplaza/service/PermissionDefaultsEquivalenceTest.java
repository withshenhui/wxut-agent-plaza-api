package com.wxut.agentplaza.service;

import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.SysUserPerm;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.mapper.SysUserPermMapper;
import com.wxut.agentplaza.service.impl.FeatureServiceImpl;
import com.wxut.agentplaza.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 迁移等价：
 * 1) 注册表角色默认集 = 旧写死集合（另见 FeatureServiceTest）；
 * 2) {@link PermissionServiceImpl#perms(Long)} 端到端（mock mappers + 真 FeatureServiceImpl）
 *    对四类角色产出的默认集 = 旧写死集合；
 * 3) 未映射角色（null/空串/历史脏值）回退 user（旧 switch 的 default 语义，fail-open）。
 */
@ExtendWith(MockitoExtension.class)
class PermissionDefaultsEquivalenceTest {

    private static final long UID = 100L;

    @Mock SysUserMapper userMapper;
    @Mock SysUserPermMapper permMapper;
    @Mock SysCollegeMapper collegeMapper;

    // ==== 旧写死集合（迁移红线的期望值，与 FeatureServiceTest 保持同源）====

    private static Set<String> userDefault() {
        return new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "teacher.form", "kb.use",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental",
            "insight.employment", "insight.export"));
    }

    private static Set<String> collegeAdminDefault() {
        return new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "teacher.form", "kb.use", "dev.entry",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental",
            "insight.employment", "insight.export", "admin.dashboard"));
    }

    private static Set<String> leaderDefault() {
        return new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "chat.all",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental",
            "insight.employment", "insight.export", "teacher.form", "kb.use", "dev.entry"));
    }

    private static Set<String> adminDefault() {
        return new HashSet<>(Arrays.asList(
            "plaza.view", "chat.college", "chat.all",
            "insight.funding", "insight.safety", "insight.academic", "insight.mental",
            "insight.employment", "insight.export", "teacher.form", "kb.use", "dev.entry",
            "admin.dashboard", "admin.agent", "admin.model", "admin.user", "admin.config",
            "admin.file", "admin.log", "admin.xixi"));
    }

    /** 构造 PermissionServiceImpl：mock 3 个 mapper + 真 FeatureServiceImpl（无配置→内置默认）。 */
    private PermissionServiceImpl svcWithRole(String role) {
        SysUser u = new SysUser();
        u.setId(UID);
        u.setRole(role);
        when(userMapper.selectById(any())).thenReturn(u);
        when(permMapper.selectList(any())).thenReturn(Collections.<SysUserPerm>emptyList());
        return new PermissionServiceImpl(userMapper, permMapper, collegeMapper, new FeatureServiceImpl(null));
    }

    // ==== (1) 注册表层等价 ====

    @Test
    void user_default_equals_old_USER_DEFAULT() {
        assertEquals(userDefault(), new FeatureServiceImpl(null).defaults("user"));
    }

    @Test
    void college_admin_has_dev_entry_and_dashboard() {
        Set<String> s = new FeatureServiceImpl(null).defaults("college_admin");
        assertTrue(s.contains("dev.entry"));
        assertTrue(s.contains("admin.dashboard"));
        assertFalse(s.contains("chat.all"));
    }

    // ==== (2) compute() 端到端等价（本任务真正改动点）====

    @Test
    void compute_user_matches_legacy_default() {
        assertEquals(userDefault(), svcWithRole("user").perms(UID));
    }

    @Test
    void compute_college_admin_matches_legacy_default() {
        assertEquals(collegeAdminDefault(), svcWithRole("college_admin").perms(UID));
    }

    @Test
    void compute_leader_matches_legacy_default() {
        assertEquals(leaderDefault(), svcWithRole("leader").perms(UID));
    }

    @Test
    void compute_admin_matches_legacy_default() {
        assertEquals(adminDefault(), svcWithRole("admin").perms(UID));
    }

    // ==== (3) 未映射角色 fail-open：回退 user（Finding 1）====

    @Test
    void compute_null_role_falls_back_to_user() {
        assertEquals(userDefault(), svcWithRole(null).perms(UID));
    }

    @Test
    void compute_empty_role_falls_back_to_user() {
        assertEquals(userDefault(), svcWithRole("").perms(UID));
    }

    @Test
    void compute_unknown_role_falls_back_to_user() {
        assertEquals(userDefault(), svcWithRole("typo_role").perms(UID));
    }
}
