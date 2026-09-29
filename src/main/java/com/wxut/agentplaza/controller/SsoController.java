package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.config.SsoProperties;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import com.wxut.agentplaza.service.SsoTicketStore;
import com.wxut.agentplaza.vo.UsernameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/sso")
@RequiredArgsConstructor
public class SsoController {

    private final AuthService authService;
    private final SsoTicketStore ticketStore;
    private final SsoProperties ssoProperties;
    private final JwtTokenProvider jwtTokenProvider;
    private final CasConfig casConfig;

    /** 锡锡平台前端错误回跳地址 */
    private static final String XIXI_FRONT_END = "http://210.28.144.49:8002";

    /**
     * SSO 入口（被 CAS 三件套过滤器保护）。
     * app=xixi        → 签发一次性 ticket，302 到锡锡后端 /xiaozhi/user/sso-login
     * app=plaza-admin → 要求 role=admin，直接签 JWT，302 到 #/admin/sso-callback
     */
    @GetMapping("/entry")
    public void entry(@RequestParam String app, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        String uid = request.getRemoteUser();
        if (uid == null || uid.isEmpty()) {
            response.sendError(401);
            return;
        }
        String cn = uid;
        Principal principal = request.getUserPrincipal();
        if (principal instanceof AttributePrincipal) {
            Object cnAttr = ((AttributePrincipal) principal).getAttributes().get("cn");
            if (cnAttr != null) {
                cn = cnAttr.toString();
            }
        }

        SysUser user = authService.findOrCreateCasUser(uid, cn);
        if (user.getStatus() != null && user.getStatus() == 0) {
            redirectError(response, app, "user_disabled");
            return;
        }

        if ("plaza-admin".equals(app)) {
            if (!"admin".equals(user.getRole())) {
                redirectError(response, app, "forbidden");
                return;
            }
            String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
            response.sendRedirect(casConfig.getFrontEndUrl()
                    + "/#/admin/sso-callback?token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8.name()));
            return;
        }

        // 默认按 xixi 处理
        String ticket = ticketStore.create(user.getUsername());
        response.sendRedirect(ssoProperties.getCallbacks().get("xixi")
                + "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8.name()));
        log.info("SSO entry: uid={} -> ticket issued for xixi", uid);
    }

    /** 锡锡服务端拿 ticket 换用户名（共享密钥保护，常量时间比较） */
    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<UsernameVO> validate(@RequestHeader(value = "X-Sso-Secret", required = false) String secret,
                                       @RequestBody Map<String, String> body) {
        if (secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                ssoProperties.getSecret().getBytes(StandardCharsets.UTF_8))) {
            return Result.error(401, "密钥校验失败");
        }
        String username = ticketStore.consume(body.get("ticket"));
        if (username == null) {
            return Result.error(401, "ticket 无效或已过期");
        }
        UsernameVO vo = new UsernameVO();
        vo.setUsername(username);
        return Result.success(vo);
    }

    private void redirectError(HttpServletResponse response, String app, String error) throws IOException {
        if ("plaza-admin".equals(app)) {
            response.sendRedirect(casConfig.getFrontEndUrl() + "/#/admin/sso-callback?error=" + error);
        } else {
            response.sendRedirect(XIXI_FRONT_END + "/#/sso-callback?error=" + error);
        }
    }
}
