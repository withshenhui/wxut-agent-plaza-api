package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.config.CasOriginResolver;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.security.Principal;

/**
 * CAS 登录相关 JSON/跳转端点。service 与前端回跳地址均按请求来源动态生成
 * （CasOriginResolver 白名单：域名 aicenter / IP 210.28.145.186:3001），
 * 保证"从哪个入口进、登录后仍回哪个入口"。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class CasAuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final CasConfig casConfig;
    private final CasOriginResolver originResolver;

    @GetMapping("/cas-login-url")
    public Result<String> getCasLoginUrl(HttpServletRequest request) throws java.io.UnsupportedEncodingException {
        String serviceUrl = originResolver.resolveServerBase(request) + "/api/v1/auth/cas-login";
        String casLoginUrl = casConfig.getServerLoginUrl() + "?service=" + URLEncoder.encode(serviceUrl, "UTF-8");
        return Result.success(casLoginUrl);
    }

    @GetMapping("/cas-logout-url")
    public Result<String> getCasLogoutUrl() {
        return Result.success(casConfig.getServerLogoutUrl());
    }

    @GetMapping("/cas-login")
    public void casLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        String frontEnd = originResolver.resolveFrontEndBase(request);
        String uid = request.getRemoteUser();
        if (uid == null || uid.isEmpty()) {
            response.sendRedirect(frontEnd + "/#/cas-callback?error=auth_failed");
            return;
        }

        try {
            Principal principal = request.getUserPrincipal();
            String cn = uid;
            if (principal instanceof AttributePrincipal) {
                AttributePrincipal aPrincipal = (AttributePrincipal) principal;
                java.util.Map<String, Object> attrs = aPrincipal.getAttributes();
                Object cnAttr = attrs.get("cn");
                if (cnAttr != null) {
                    cn = cnAttr.toString();
                }
            }

            SysUser user = authService.findOrCreateCasUser(uid, cn);
            String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
            response.sendRedirect(frontEnd + "/#/cas-callback?token=" + token);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (errorMsg.contains("禁用")) {
                response.sendRedirect(frontEnd + "/#/cas-callback?error=user_disabled");
            } else {
                response.sendRedirect(frontEnd + "/#/cas-callback?error=auth_failed");
            }
        }
    }
}
