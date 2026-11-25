package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.service.AgentService;
import com.wxut.agentplaza.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/v1/agents 列表的「待分配」过滤（纯逻辑，mock 依赖）：
 * 已登录 + pending=1 → 强制 category_id=8（制度建设）；
 * 正常用户 / 匿名（principal 非 Long）/ auth 为空 → 保持原样（匿名=全量，不破坏游客）。
 */
@ExtendWith(MockitoExtension.class)
class AgentPendingFilterTest {

    @Mock AgentService agentService;
    @Mock PermissionService permissionService;

    private AgentQueryDTO search(Authentication auth, AgentQueryDTO dto) {
        new AgentController(agentService, permissionService).search(dto, auth);
        return dto;
    }

    @Test
    void pending_user_forced_to_institution_category() {
        when(permissionService.isPending(7L)).thenReturn(true);
        AgentQueryDTO dto = search(new UsernamePasswordAuthenticationToken(7L, null), new AgentQueryDTO());
        assertEquals(Long.valueOf(8L), dto.getCategoryId());
    }

    @Test
    void normal_user_keeps_requested_category() {
        when(permissionService.isPending(7L)).thenReturn(false);
        AgentQueryDTO dto = new AgentQueryDTO();
        dto.setCategoryId(3L);
        search(new UsernamePasswordAuthenticationToken(7L, null), dto);
        assertEquals(Long.valueOf(3L), dto.getCategoryId());
    }

    @Test
    void anonymous_principal_is_ignored() {
        AgentQueryDTO dto = search(new UsernamePasswordAuthenticationToken("anonymousUser", null), new AgentQueryDTO());
        assertNull(dto.getCategoryId());
        verify(permissionService, never()).isPending(any());
    }

    @Test
    void null_auth_is_ignored() {
        AgentQueryDTO dto = search(null, new AgentQueryDTO());
        assertNull(dto.getCategoryId());
        verify(permissionService, never()).isPending(any());
    }
}
