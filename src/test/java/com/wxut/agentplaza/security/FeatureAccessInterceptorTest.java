package com.wxut.agentplaza.security;

import com.wxut.agentplaza.model.FeatureDef;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    /** Build a perm-gated teacher feature: api="/api/v1/form", permKey="teacher.form". */
    private FeatureDef permFeature() {
        FeatureDef f = new FeatureDef();
        f.setKey("teacher");
        f.setEnabled(true);
        f.setGate("perm");
        f.setPermKey("teacher.form");
        f.setApi("/api/v1/form");
        return f;
    }

    /** Authenticate with a Long principal (userId) via a non-anonymous token. */
    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
    }

    /** Authenticate as admin: Long principal + ROLE_ADMIN authority (同 JwtAuthenticationFilter). */
    private void authenticateAsAdmin(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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

    @Test void perm_gate_unauthenticated_returns_401() throws Exception {
        setup(permFeature());
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(it.preHandle(new MockHttpServletRequest("GET","/api/v1/form/list"), res, new Object()));
        assertEquals(401, res.getStatus());
    }

    @Test void perm_gate_authenticated_without_perm_returns_403() throws Exception {
        setup(permFeature());
        authenticateAs(Long.valueOf(7L));
        when(ps.has(anyLong(), eq("teacher.form"))).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(it.preHandle(new MockHttpServletRequest("GET","/api/v1/form/list"), res, new Object()));
        assertEquals(403, res.getStatus());
        verify(ps).has(eq(7L), eq("teacher.form"));
    }

    @Test void perm_gate_authenticated_with_perm_passes() throws Exception {
        setup(permFeature());
        authenticateAs(Long.valueOf(7L));
        when(ps.has(anyLong(), eq("teacher.form"))).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(it.preHandle(new MockHttpServletRequest("GET","/api/v1/form/list"), res, new Object()));
        assertEquals(200, res.getStatus());
        verify(ps).has(eq(7L), eq("teacher.form"));
    }

    @Test void admin_passes_even_when_feature_disabled() throws Exception {
        // F7：停用功能不得锁死后台（api 前缀同时承载后台 CRUD）→ 管理员放行
        FeatureDef f = new FeatureDef(); f.setKey("models"); f.setEnabled(false); setup(f);
        authenticateAsAdmin(Long.valueOf(1L));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(it.preHandle(new MockHttpServletRequest("POST","/api/v1/models"), res, new Object()));
        assertEquals(200, res.getStatus());
    }
}
