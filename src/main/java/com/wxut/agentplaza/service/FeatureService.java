package com.wxut.agentplaza.service;

import com.wxut.agentplaza.model.FeatureDef;

import java.util.List;
import java.util.Set;

/** 前台功能注册表：内置默认（=当前行为）+ sys_config: v2.features 覆盖。 */
public interface FeatureService {

    /** 角色默认可用的权限键集合（迁移等价红线：与旧写死集合逐字一致）。 */
    Set<String> defaults(String role);

    /** 可逐人申请开通的权限键（未被所有角色默认授予的 perm 门），如 dev.entry。 */
    Set<String> grantablePerms();

    /** 按后端 URL 最长前缀匹配功能定义；未命中返回 null。 */
    FeatureDef featureOfPath(String requestPath);

    /** 解析后的功能定义列表（含 children）。 */
    List<FeatureDef> resolve();

    /** 清空缓存（配置变更后调用）。 */
    void evict();
}
