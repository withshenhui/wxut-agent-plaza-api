package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysOperationLogMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.SysOperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysOperationLogServiceImpl implements SysOperationLogService {

    private final SysOperationLogMapper logMapper;
    private final SysUserMapper userMapper;

    @Override
    public PageResult<Map<String, Object>> listLogs(String username, String operation, int page, int size) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isEmpty()) wrapper.like(SysOperationLog::getUsername, username);
        if (operation != null && !operation.isEmpty()) wrapper.like(SysOperationLog::getOperation, operation);
        wrapper.orderByDesc(SysOperationLog::getCreateTime);
        Page<SysOperationLog> p = logMapper.selectPage(new Page<>(page, size), wrapper);

        // 操作人显示"姓名（工号）"：批量取本页涉及用户的昵称
        Set<String> names = p.getRecords().stream()
                .map(SysOperationLog::getUsername).filter(u -> u != null && !u.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> nickOf = new HashMap<>();
        if (!names.isEmpty()) {
            for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getUsername, names).select(SysUser::getUsername, SysUser::getNickname))) {
                nickOf.put(u.getUsername(), u.getNickname() == null ? "" : u.getNickname());
            }
        }
        // 早期日志 username 存的是 sys_user.id（如 1=admin、3=工号），按工号查不到的纯数字补按 id 反查
        java.util.Set<String> missing = names.stream().filter(n -> !nickOf.containsKey(n) && n.matches("\\d+"))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getId, missing).select(SysUser::getId, SysUser::getUsername, SysUser::getNickname))) {
                if (u.getUsername() != null && !u.getUsername().isEmpty()) {
                    nickOf.put(String.valueOf(u.getId()), u.getUsername() + "·" + (u.getNickname() == null ? "" : u.getNickname()));
                }
            }
        }
        PageResult<Map<String, Object>> out = new PageResult<>();
        out.setTotal(p.getTotal());
        out.setPage(p.getCurrent());
        out.setSize(p.getSize());
        out.setRecords(p.getRecords().stream().map(l -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId());
            m.put("username", l.getUsername());
            String nick = nickOf.getOrDefault(l.getUsername(), "");
            if (nick.contains("·")) {
                String[] parts = nick.split("·", 2);
                m.put("operatorName", parts[1] + "（" + parts[0] + "）");
            } else {
                m.put("operatorName", nick.isEmpty() ? l.getUsername() : nick + "（" + l.getUsername() + "）");
            }
            m.put("operation", l.getOperation());
            m.put("method", l.getMethod());
            m.put("params", l.getParams());
            m.put("ip", l.getIp());
            m.put("createTime", l.getCreateTime());
            return m;
        }).collect(Collectors.toList()));
        return out;
    }

    @Override
    public void save(SysOperationLog log) {
        logMapper.insert(log);
    }
}
