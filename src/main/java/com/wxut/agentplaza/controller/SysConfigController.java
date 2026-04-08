package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysConfig;
import com.wxut.agentplaza.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService configService;

    @GetMapping
    @Operation(summary = "获取所有配置")
    public Result<List<SysConfig>> list() {
        return Result.success(configService.listAll());
    }

    @GetMapping("/{key}")
    @Operation(summary = "获取配置项")
    public Result<SysConfig> getByKey(@PathVariable String key) {
        return Result.success(configService.getByKey(key));
    }

    @PutMapping("/{key}")
    @Operation(summary = "更新配置项")
    @OperationLog("更新系统配置")
    public Result<Void> update(@PathVariable String key, @RequestBody String value) {
        configService.updateByKey(key, value);
        return Result.success();
    }
}
