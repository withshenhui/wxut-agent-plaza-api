package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.config.CasOriginResolver;
import com.wxut.agentplaza.config.SsoProperties;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.SsoTicketStore;
import com.wxut.agentplaza.vo.TicketVO;
import com.wxut.agentplaza.vo.UsernameVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final SysUserMapper sysUserMapper;
    private final CasOriginResolver originResolver;
    private final PermissionService permissionService;

    /** 锡锡平台前端错误回跳地址 */
    private static final String XIXI_FRONT_END = "http://210.28.144.49:8002";

    /**
     * SSO 入口（被 CAS 三件套过滤器保护）。
     * app=xixi        → 签发一次性 ticket，302 到锡锡后端 /xiaozhi/user/sso-login
     * app=plaza-admin → 要求 role=admin，直接签 JWT，302 到 #/admin/sso-callback
     * app=dify        → 签发一次性 ticket（含显示名），302 到 Dify /console/api/passport/sso-login
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

        try {
            SysUser user = authService.findOrCreateCasUser(uid, cn);
            if (user.getStatus() != null && user.getStatus() == 0) {
                redirectError(request, response, app, "user_disabled");
                return;
            }

            if ("plaza-admin".equals(app)) {
                if (!"admin".equals(user.getRole())) {
                    redirectError(request, response, app, "forbidden");
                    return;
                }
                String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getCollegeId(), true);
                // 前端回跳按请求来源动态生成：IP 进回 IP，域名进回域名
                response.sendRedirect(originResolver.resolveFrontEndBase(request)
                        + "/#/admin/sso-callback?token="
                        + URLEncoder.encode(token, StandardCharsets.UTF_8.name()));
                return;
            }

            if ("xixibuddy".equals(app)) {
                String xbCallback = ssoProperties.getCallbacks().get("xixibuddy");
                if (xbCallback == null || xbCallback.isEmpty()) {
                    log.error("SSO entry: xixibuddy callback not configured (sso.callbacks.xixibuddy)");
                    response.sendError(500, "xixibuddy callback not configured");
                    return;
                }
                String ticket = ticketStore.create(user.getUsername(), user.getNickname());
                StringBuilder url = new StringBuilder(xbCallback)
                        .append(xbCallback.contains("?") ? "&" : "?")
                        .append("ticket=")
                        .append(URLEncoder.encode(ticket, StandardCharsets.UTF_8.name()));
                response.sendRedirect(url.toString());
                log.info("SSO entry: uid={} -> ticket issued for xixibuddy", uid);
                return;
            }

            if ("dify".equals(app)) {
                // 开发平台入口权限强制：dev.entry 实行申请开通制（由所在学院/部门向信息化与
                // 数据服务中心申请）。有 CAS 账号 ≠ 有开发平台权限；URL 直打同样拦截（纵深防御）。
                if (!permissionService.has(user.getId(), "dev.entry")) {
                    log.warn("SSO entry dify denied: uid={} has no dev.entry perm", uid);
                    response.sendError(403, "智能体开发平台实行申请开通制：请由所在学院/部门向信息化与数据服务中心申请，开通后使用开发者账号登录。");
                    return;
                }
                // 开发平台不走 Dify passport 单点票据（回调已下线）：跳转到平台登录页，
                // 由信息中心开通的开发者账号登录。
                response.sendRedirect("https://aiagent.wxut.edu.cn/apps");
                log.info("SSO entry: uid={} -> dev platform (account login, no sso ticket)", uid);
                return;
            }

            // 默认按 xixi 处理
            String ticket = ticketStore.create(user.getUsername());
            response.sendRedirect(ssoProperties.getCallbacks().get("xixi")
                    + "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8.name()));
            log.info("SSO entry: uid={} -> ticket issued for xixi", uid);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (errorMsg.contains("禁用")) {
                redirectError(request, response, app, "user_disabled");
            } else {
                redirectError(request, response, app, "auth_failed");
            }
        }
    }

    /** 锡锡/Dify 服务端拿 ticket 换用户名（共享密钥保护，常量时间比较） */
    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<UsernameVO> validate(@RequestHeader(value = "X-Sso-Secret", required = false) String secret,
                                       @RequestBody Map<String, String> body) {
        if (secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                ssoProperties.getSecret().getBytes(StandardCharsets.UTF_8))) {
            return Result.error(401, "密钥校验失败");
        }
        SsoTicketStore.Entry entry = ticketStore.consume(body.get("ticket"));
        if (entry == null) {
            return Result.error(401, "ticket 无效或已过期");
        }
        UsernameVO vo = new UsernameVO();
        vo.setUsername(entry.username);
        vo.setName(entry.name != null && !entry.name.isEmpty() ? entry.name : entry.username);
        return Result.success(vo);
    }

    /**
     * 管理后台内嵌锡锡平台用：当前登录管理员凭 JWT 直接换一次性 ticket，
     * 免 CAS 会话（管理员可能用账号密码登录，无学校 CAS 会话）。
     */
    @PostMapping("/ticket")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TicketVO> ticket(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return Result.error(401, "用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.error(403, "账号已被禁用");
        }
        String ticket = ticketStore.create(user.getUsername());
        log.info("SSO ticket issued via admin token: username={}", user.getUsername());
        TicketVO vo = new TicketVO();
        vo.setTicket(ticket);
        return Result.success(vo);
    }

    private void redirectError(HttpServletRequest request, HttpServletResponse response, String app, String error) throws IOException {
        if ("xixibuddy".equals(app)) {
            String xbCallback = ssoProperties.getCallbacks().get("xixibuddy");
            response.sendRedirect((xbCallback != null ? xbCallback : "/") + (xbCallback != null && xbCallback.contains("?") ? "&" : "?") + "error=" + error);
        } else if ("plaza-admin".equals(app)) {
            response.sendRedirect(originResolver.resolveFrontEndBase(request) + "/#/admin/sso-callback?error=" + error);
        } else if ("dify".equals(app)) {
            // 错误也发到 Dify 回调但不带 ticket，由 passport 端点兜底 302 到 Dify 自己的登录页
            String difyCallback = ssoProperties.getCallbacks().get("dify");
            response.sendRedirect(difyCallback != null ? difyCallback : "/signin");
        } else {
            response.sendRedirect(XIXI_FRONT_END + "/#/sso-callback?error=" + error);
        }
    }
}
