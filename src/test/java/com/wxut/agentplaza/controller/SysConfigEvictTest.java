package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.SysConfigService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class SysConfigEvictTest {

    /** F2：保存 v2.features 后必须立即失效注册表缓存（否则后台 60s TTL 内仍返回旧注册表）。 */
    @Test
    void updating_v2_features_evicts_registry_cache() {
        SysConfigService configService = mock(SysConfigService.class);
        FeatureService featureService = mock(FeatureService.class);
        SysConfigController controller = new SysConfigController(configService, featureService);

        controller.update("v2.features", "{\"features\":[]}");

        verify(configService).updateByKey("v2.features", "{\"features\":[]}");
        verify(featureService).evict();
    }

    /** 负例：非 v2.features 的配置更新不应触发注册表缓存失效。 */
    @Test
    void updating_other_key_does_not_evict() {
        SysConfigService configService = mock(SysConfigService.class);
        FeatureService featureService = mock(FeatureService.class);
        SysConfigController controller = new SysConfigController(configService, featureService);

        controller.update("v2.nav", "[]");

        verify(configService).updateByKey("v2.nav", "[]");
        verify(featureService, never()).evict();
    }
}
