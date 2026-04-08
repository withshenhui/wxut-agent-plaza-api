package com.wxut.agentplaza.service;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.entity.SysOperationLog;

public interface SysOperationLogService {
    PageResult<SysOperationLog> listLogs(String username, String operation, int page, int size);
    void save(SysOperationLog log);
}
