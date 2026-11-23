package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysConfig;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService configService;
    private final FeatureService featureService;

    /**
     * 允许未登录读取的配置 key 白名单。
     * 背景：SecurityConfig 对 GET /api/v1/configs/* 放行（前台需要读站点名等），
     * 曾导致 llm.api_key / llm.providers 等含密钥配置可被匿名读取，故在控制器层收紧。
     */
    private static final Set<String> PUBLIC_READ_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("site_name", "site_subtitle", "site_footer", "menu.links", "guest.mode", "v2.nav", "v2.features")));

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    @GetMapping
    @Operation(summary = "获取所有配置")
    public Result<List<SysConfig>> list() {
        return Result.success(configService.listAll());
    }

    @GetMapping("/{key}")
    @Operation(summary = "获取配置项（未登录仅限白名单 key）")
    public Result<SysConfig> getByKey(@PathVariable String key) {
        if (!isAuthenticated() && !PUBLIC_READ_KEYS.contains(key)) {
            return Result.error(403, "无权读取该配置");
        }
        return Result.success(configService.getByKey(key));
    }

    /**
     * 当前生效的大模型名（公开、脱敏：仅模型名，不含地址与密钥），
     * 供前台"智能体信息"展示后台大模型配置当前启用的模型。
     */
    @GetMapping("/llm/current-model")
    @Operation(summary = "当前生效大模型名（公开，脱敏）")
    public Result<String> currentModel() {
        SysConfig c = configService.getByKey("llm.model");
        return Result.success(c != null ? c.getConfigValue() : "");
    }

    @PutMapping("/{key}")
    @Operation(summary = "更新配置项")
    @OperationLog("更新系统配置")
    public Result<Void> update(@PathVariable String key, @RequestBody String value) {
        configService.updateByKey(key, value);
        // v2.features 变更后立即失效注册表缓存，避免后台仍按旧的 60s TTL 返回旧注册表
        // （后台 UI 提示"刷新后生效"，此处保证刷新即刻生效）。
        if ("v2.features".equals(key)) {
            featureService.evict();
        }
        return Result.success();
    }
}
