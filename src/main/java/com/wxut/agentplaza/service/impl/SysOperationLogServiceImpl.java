package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.mapper.SysOperationLogMapper;
import com.wxut.agentplaza.service.SysOperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SysOperationLogServiceImpl implements SysOperationLogService {

    private final SysOperationLogMapper logMapper;

    @Override
    public PageResult<SysOperationLog> listLogs(String username, String operation, int page, int size) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isEmpty()) wrapper.like(SysOperationLog::getUsername, username);
        if (operation != null && !operation.isEmpty()) wrapper.like(SysOperationLog::getOperation, operation);
        wrapper.orderByDesc(SysOperationLog::getCreateTime);
        return PageResult.of(logMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @Override
    public void save(SysOperationLog log) {
        logMapper.insert(log);
    }
}
