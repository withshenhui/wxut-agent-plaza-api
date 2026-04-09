package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wxut.agentplaza.common.Result;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class CasAuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final CasConfig casConfig;

    @GetMapping("/cas-login-url")
    public Result<String> getCasLoginUrl() throws IOException {
        String serviceUrl = casConfig.getClientServerUrl() + "/api/v1/auth/cas-login";
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
        String uid = request.getRemoteUser();
        if (uid == null || uid.isEmpty()) {
            response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=auth_failed");
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
            response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?token=" + token);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (errorMsg.contains("禁用")) {
                response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=user_disabled");
            } else {
                response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=auth_failed");
            }
        }
    }
}
