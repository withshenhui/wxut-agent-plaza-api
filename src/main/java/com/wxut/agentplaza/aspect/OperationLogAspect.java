package com.wxut.agentplaza.aspect;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.service.SysOperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint point, OperationLog operationLog) throws Throwable {
        Object result = point.proceed();
        try {
            saveLog(point, operationLog.value());
        } catch (Exception e) {
            log.warn("Failed to save operation log", e);
        }
        return result;
    }

    private void saveLog(ProceedingJoinPoint point, String operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysOperationLog log = new SysOperationLog();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            log.setUserId((Long) auth.getPrincipal());
            log.setUsername(auth.getName());
        }
        log.setOperation(operation);
        MethodSignature signature = (MethodSignature) point.getSignature();
        log.setMethod(signature.getDeclaringTypeName() + "." + signature.getName());
        try {
            log.setParams(objectMapper.writeValueAsString(point.getArgs()));
        } catch (Exception e) {
            log.setParams("serialize failed");
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            log.setIp(request.getRemoteAddr());
        }
        operationLogService.save(log);
    }
}
