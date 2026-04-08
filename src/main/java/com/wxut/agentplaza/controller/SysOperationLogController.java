package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.service.SysOperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "操作日志")
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class SysOperationLogController {

    private final SysOperationLogService logService;

    @GetMapping
    @Operation(summary = "获取操作日志列表")
    public Result<PageResult<SysOperationLog>> list(@RequestParam(required = false) String username,
                                                     @RequestParam(required = false) String operation,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return Result.success(logService.listLogs(username, operation, page, size));
    }
}
