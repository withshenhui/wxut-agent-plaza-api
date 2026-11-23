package com.wxut.agentplaza.security;

import com.wxut.agentplaza.model.FeatureDef;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeatureAccessInterceptorTest {

    private FeatureService fs;
    private PermissionService ps;
    private FeatureAccessInterceptor it;

    private void setup(FeatureDef f) {
        fs = mock(FeatureService.class); ps = mock(PermissionService.class);
        when(fs.featureOfPath(anyString())).thenReturn(f);
        it = new FeatureAccessInterceptor(fs, ps);
    }

    @Test void disabled_feature_returns_403() throws Exception {
        FeatureDef f = new FeatureDef(); f.setKey("kb"); f.setEnabled(false); setup(f);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(it.preHandle(new MockHttpServletRequest("GET","/api/v1/kb/list"), res, new Object()));
        assertEquals(403, res.getStatus());
    }

    @Test void login_gate_unauthenticated_returns_401() throws Exception {
        FeatureDef f = new FeatureDef(); f.setKey("kb"); f.setEnabled(true); f.setGate("login"); setup(f);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(it.preHandle(new MockHttpServletRequest("GET","/api/v1/kb/list"), res, new Object()));
        assertEquals(401, res.getStatus());
    }

    @Test void no_matching_feature_passes() throws Exception {
        setup(null);
        assertTrue(it.preHandle(new MockHttpServletRequest("GET","/api/v1/auth/login"), new MockHttpServletResponse(), new Object()));
    }
}
