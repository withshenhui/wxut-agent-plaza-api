package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import com.wxut.agentplaza.service.AgentService;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.SysUserService;
import com.wxut.agentplaza.vo.AgentVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 企微仿真预览接口的纯逻辑（mock 依赖）：
 * perms/scope/agents 一律按目标 userId 计算；待分配用户 agents 强制 category_id=8（制度建设）。
 */
@ExtendWith(MockitoExtension.class)
class AdminPreviewControllerTest {

    @Mock SysUserService userService;
    @Mock PermissionService permissionService;
    @Mock AgentService agentService;
    @Mock SysCollegeMapper collegeMapper;

    private AdminPreviewController controller() {
        return new AdminPreviewController(userService, permissionService, agentService, collegeMapper);
    }

    private SysUser user(Long id, Integer pending, Integer collegeId) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("18351589157");
        u.setNickname("测试用户");
        u.setRole("user");
        u.setPending(pending);
        u.setCollegeId(collegeId);
        return u;
    }

    private PageResult<AgentVO> page(AgentVO... vos) {
        PageResult<AgentVO> p = new PageResult<>();
        p.setRecords(Arrays.asList(vos));
        return p;
    }

    @Test
    void pending_user_agents_forced_to_institution_and_perms_from_target() {
        when(userService.getUserById(27L)).thenReturn(user(27L, 1, null));
        when(permissionService.isPending(27L)).thenReturn(true);
        when(permissionService.perms(27L)).thenReturn(new HashSet<>(Collections.singletonList("plaza.view")));
        Map<String, Object> scope = new HashMap<>();
        scope.put("scope", "college");
        scope.put("collegeId", -1);
        when(permissionService.scope(27L)).thenReturn(scope);
        when(agentService.search(any(AgentQueryDTO.class))).thenReturn(page(new AgentVO()));

        ArgumentCaptor<AgentQueryDTO> cap = ArgumentCaptor.forClass(AgentQueryDTO.class);
        Result<Map<String, Object>> r = controller().preview(27L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = r.getData();
        assertEquals("college", data.get("scope"));
        assertEquals(Collections.singletonList("plaza.view"), data.get("perms"));

        org.mockito.Mockito.verify(agentService).search(cap.capture());
        assertEquals(Long.valueOf(8L), cap.getValue().getCategoryId());
        assertEquals(Integer.valueOf(1), cap.getValue().getIncludeBuilding());
    }

    @Test
    void normal_user_agents_not_category_filtered() {
        when(userService.getUserById(27L)).thenReturn(user(27L, 0, 3));
        when(permissionService.isPending(27L)).thenReturn(false);
        when(permissionService.perms(27L)).thenReturn(new HashSet<>(Arrays.asList("plaza.view", "chat.college")));
        Map<String, Object> scope = new HashMap<>();
        scope.put("scope", "all");
        when(permissionService.scope(27L)).thenReturn(scope);
        when(agentService.search(any(AgentQueryDTO.class))).thenReturn(page(new AgentVO()));

        ArgumentCaptor<AgentQueryDTO> cap = ArgumentCaptor.forClass(AgentQueryDTO.class);
        Result<Map<String, Object>> r = controller().preview(27L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = r.getData();
        assertEquals("all", data.get("scope"));
        assertTrue(((java.util.List<?>) data.get("perms")).contains("chat.college"));

        org.mockito.Mockito.verify(agentService).search(cap.capture());
        assertNull(cap.getValue().getCategoryId()); // 非待分配 → 不加分类过滤
    }
}
