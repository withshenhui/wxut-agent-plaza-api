package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.ModelQueryDTO;
import com.wxut.agentplaza.service.ModelService;
import com.wxut.agentplaza.vo.ModelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "模型管理")
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping
    @Operation(summary = "搜索模型")
    public Result<PageResult<ModelVO>> search(ModelQueryDTO dto) {
        return Result.success(modelService.search(dto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模型详情")
    public Result<ModelVO> detail(@PathVariable Long id) {
        return Result.success(modelService.getDetail(id));
    }

    @PostMapping
    @Operation(summary = "创建模型")
    @OperationLog("创建模型")
    public Result<Void> create(@RequestBody ModelVO dto) {
        modelService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新模型")
    @OperationLog("更新模型")
    public Result<Void> update(@PathVariable Long id, @RequestBody ModelVO dto) {
        modelService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型")
    @OperationLog("删除模型")
    public Result<Void> delete(@PathVariable Long id) {
        modelService.delete(id);
        return Result.success();
    }
}
