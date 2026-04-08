package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.AgentCategory;
import com.wxut.agentplaza.service.AgentCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "智能体分类")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class AgentCategoryController {

    private final AgentCategoryService categoryService;

    @GetMapping
    @Operation(summary = "获取所有分类")
    public Result<List<AgentCategory>> list() {
        return Result.success(categoryService.listAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取分类详情")
    public Result<AgentCategory> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建分类")
    public Result<Void> create(@RequestBody AgentCategory category) {
        categoryService.create(category);
        return Result.success();
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分类")
    public Result<Void> update(@PathVariable Long id, @RequestBody AgentCategory category) {
        categoryService.update(id, category);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
