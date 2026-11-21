package com.wxut.agentplaza.service;

import java.util.Map;
import java.util.Set;

/**
 * 功能权限与数据范围计算。
 * 权限集 = 角色默认集 ∪ 个人加授(granted=1) − 个人显式剥夺(granted=0)。
 */
public interface PermissionService {

    /** 用户全部权限键（含角色默认） */
    Set<String> perms(Long userId);

    boolean has(Long userId, String permKey);

    /** 无权限抛 BusinessException(403) */
    void assertPerm(Long userId, String permKey);

    /**
     * 数据范围。
     * @return {scope: "college"|"all", collegeId: 学院id(all 时为0), collegeName}
     */
    Map<String, Object> scope(Long userId);

    /** 洞察接口的学院过滤：college 范围强制本人学院，all 范围放行请求参数 */
    String resolveCollegeFilter(Long userId, String requested);

    /** 学院管理员校验（college_admin/admin） */
    void assertCollegeAdmin(Long userId);

    /** 清除某用户权限缓存（权限变更后调用） */
    void evict(Long userId);
}
