package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.AgentCreateDTO;
import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.service.AgentService;
import com.wxut.agentplaza.vo.AgentDetailVO;
import com.wxut.agentplaza.vo.AgentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "智能体管理")
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    @Operation(summary = "搜索智能体")
    public Result<PageResult<AgentVO>> search(AgentQueryDTO dto) {
        return Result.success(agentService.search(dto));
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
