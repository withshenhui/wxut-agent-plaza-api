package com.wxut.agentplaza.service;

/**
 * 按工号从数据中台数仓自动推导教职工学院归属。
 * 优先级：评教主单位(ksdwmc) → 督导/听课所属部门(jsssbm) → 部门GUID字典(bmdm)。
 * 数仓不可达时不抛异常（返回 null，下次登录再试）。
 */
public interface CollegeSyncService {

    /**
     * 推导并落库（幂等：已有 college_id 或 synced_at 的跳过，force=true 强制重推）。
     * @return 落库后的 SysUser；数仓不可达返回原样用户
     */
    com.wxut.agentplaza.entity.SysUser syncIfNeeded(com.wxut.agentplaza.entity.SysUser user, boolean force);

    /** 仅推导学院名（不落库），给手动重推/测试用 */
    String detectCollegeName(String employeeNo);
}
