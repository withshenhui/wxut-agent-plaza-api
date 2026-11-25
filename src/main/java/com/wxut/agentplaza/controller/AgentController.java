package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.AgentCreateDTO;
import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.service.AgentService;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.vo.AgentDetailVO;
import com.wxut.agentplaza.vo.AgentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "智能体管理")
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    /** 「制度建设」分类 id：待分配用户仅可见该分类智能体 */
    private static final Long PENDING_CATEGORY_ID = 8L;

    private final AgentService agentService;
    private final PermissionService permissionService;

    @GetMapping
    @Operation(summary = "搜索智能体")
    public Result<PageResult<AgentVO>> search(AgentQueryDTO dto, Authentication auth) {
        // 端点 permitAll：匿名/游客 auth 为空（或匿名 principal）→ 保持全量；
        // 已登录且「待分配」→ 仅返回「制度建设」分类
        Long userId = currentUserId(auth);
        if (userId != null && permissionService.isPending(userId)) {
            dto.setCategoryId(PENDING_CATEGORY_ID);
        }
        return Result.success(agentService.search(dto));
    }

    /** 从认证上下文取用户 id：非登录态（匿名）返回 null，不破坏游客行为 */
    private Long currentUserId(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof Long ? (Long) principal : null;
    }

    @GetMapping("/recommended")
    @Operation(summary = "获取推荐智能体")
    public Result<List<AgentVO>> recommended() {
        return Result.success(agentService.listRecommended());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取智能体详情")
    public Result<AgentDetailVO> detail(@PathVariable Long id) {
        return Result.success(agentService.getDetail(id));
    }

    @PostMapping
    @Operation(summary = "创建智能体")
    @OperationLog("创建智能体")
    public Result<Void> create(@Valid @RequestBody AgentCreateDTO dto) {
        agentService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新智能体")
    @OperationLog("更新智能体")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AgentCreateDTO dto) {
        agentService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除智能体")
    @OperationLog("删除智能体")
    public Result<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return Result.success();
    }
}
