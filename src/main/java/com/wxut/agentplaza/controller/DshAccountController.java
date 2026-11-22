package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.config.CasOriginResolver;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XixiBuddy 桌面客户端的账号协议端点（DeepSeek Harness account 协议的校园版实现）。
 *
 * 桌面客户端的欢迎窗登录走固定协议：POST /auth-api/v0/dsh/auth_init 拿 authorize_url →
 * 系统浏览器打开 {origin}/dsh/authorize → 本控制器 302 学校 CAS（ids.wxut.edu.cn）→
 * CAS 回调带 ticket → serviceValidate 换工号 → 302 回客户端回环 redirect_uri?code →
 * 客户端 POST /auth-api/v0/dsh/auth_exchange 换长期 token。
 *
 * 路径全部在域名根下（nginx location / 已反代 Tomcat /agent-plaza/，无需 nginx 改动），
 * 因为客户端的 platformOrigin 校验要求纯 origin（无路径前缀）。会话为服务端内存态，
 * Tomcat 重启后客户端收 40003 重新走登录即可。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DshAccountController {

    private final AuthService authService;
    private final CasConfig casConfig;
    private final CasOriginResolver originResolver;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 客户端回环回调仅允许 loopback 形态（与 dsh loginOrigin 同口径），防开放重定向 */
    private static final Pattern LOOPBACK_CALLBACK =
            Pattern.compile("^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?/oauth/callback$");
    private static final Pattern CAS_USER =
            Pattern.compile("<cas:authenticationSuccess>.*?<cas:user>([^<]+)</cas:user>", Pattern.DOTALL);

    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;   // authorize 链路最长 10 分钟
    private static final long GRANT_TTL_MS = 5 * 60 * 1000L;      // code 换 token 窗口
    private static final long SESSION_TTL_MS = 12 * 60 * 60 * 1000L; // 登录态 12 小时

    /** state -> 回调地址与过期时间 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** code -> 用户 */
    private final Map<String, Grant> granted = new ConcurrentHashMap<>();
    /** token -> 用户 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static class Pending {
        final String redirectUri;
        final long expiresAt;
        Pending(String redirectUri, long expiresAt) {
            this.redirectUri = redirectUri;
            this.expiresAt = expiresAt;
        }
    }
    private static class Grant {
        final String username;
        final String name;
        final long expiresAt;
        Grant(String username, String name, long expiresAt) {
            this.username = username;
            this.name = name;
            this.expiresAt = expiresAt;
        }
    }
    private static class Session {
        final String username;
        final String name;
        final long expiresAt;
        Session(String username, String name, long expiresAt) {
            this.username = username;
            this.name = name;
            this.expiresAt = expiresAt;
        }
    }

    // ---------------------------------------------------------------------
    // 协议端点（客户端调用）
    // ---------------------------------------------------------------------

    /** 登录初始化：登记回调，返回 authorize_url（必须与 platformOrigin 同源、path=/dsh/authorize）。
     *  协议要求 state 原样透传：客户端生成随机 state 发来，authorize 回调时带回同一个值，
     *  客户端用 timingSafeEqual 字节级比对（不一致即 400），因此这里必须用客户端的 state 作登记键。 */
    @PostMapping(value = "/auth-api/v0/dsh/auth_init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authInit(@RequestBody Map<String, String> body, HttpServletRequest request) {
        sweep();
        String redirectUri = body.getOrDefault("redirect_uri", "");
        String clientState = body.getOrDefault("state", "");
        if (!LOOPBACK_CALLBACK.matcher(redirectUri).matches()) {
            log.warn("dsh auth_init: rejected redirect_uri={}", redirectUri);
            return error(40003);
        }
        if (!clientState.matches("[A-Za-z0-9_-]{16,128}")) {
            log.warn("dsh auth_init: rejected state format, length={}", clientState.length());
            return error(40003);
        }
        pending.put(clientState, new Pending(redirectUri, System.currentTimeMillis() + PENDING_TTL_MS));
        String origin = originResolver.resolveOrigin(request);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("authorize_url", origin + "/dsh/authorize?state=" + urlEncode(clientState));
        biz.put("authorize_id", clientState);
        biz.put("expires_in", PENDING_TTL_MS / 1000);
        log.info("dsh auth_init: state={} callback={}", abbreviate(clientState), redirectUri);
        return envelope(biz);
    }

    /** 浏览器授权页：无 ticket 跳 CAS，有 ticket 验证后回客户端 */
    @GetMapping("/dsh/authorize")
    public void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        sweep();
        response.setHeader("Cache-Control", "no-store");
        String state = request.getParameter("state");
        Pending p = state == null ? null : pending.get(state);
        if (p == null) {
            response.setStatus(400);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("登录会话已过期，请回到 XixiBuddy 客户端重新登录。");
            return;
        }
        String origin = originResolver.resolveOrigin(request);
        String service = origin + "/dsh/authorize?state=" + urlEncode(state);
        String ticket = request.getParameter("ticket");
        if (ticket == null || ticket.isEmpty()) {
            response.sendRedirect(casConfig.getServerLoginUrl() + "?service=" + urlEncode(service));
            return;
        }
        // CAS ticket 验证：service 必须与跳转登录页时完全一致
        String uid = validateTicket(ticket, service);
        if (uid == null) {
            response.setStatus(502);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("统一身份认证校验失败，请重试。");
            return;
        }
        SysUser user = authService.findOrCreateCasUser(uid, uid);
        String code = randomToken();
        granted.put(code, new Grant(user.getUsername(), displayName(user), System.currentTimeMillis() + GRANT_TTL_MS));
        pending.remove(state);
        StringBuilder back = new StringBuilder(p.redirectUri);
        back.append(p.redirectUri.contains("?") ? "&" : "?")
            .append("code=").append(urlEncode(code)).append("&state=").append(urlEncode(state));
        log.info("dsh authorize: CAS login OK uid={} -> client callback", uid);
        response.sendRedirect(back.toString());
    }

    /** code 换 token（一次性），返回客户端需要的会话材料 */
    @PostMapping(value = "/auth-api/v0/dsh/auth_exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authExchange(@RequestBody Map<String, String> body, HttpServletRequest request) {
        sweep();
        String code = body.getOrDefault("code", "");
        Grant g = code.isEmpty() ? null : granted.remove(code);
        if (g == null) {
            return error(40003);
        }
        String token = "cas-" + randomToken();
        sessions.put(token, new Session(g.username, g.name, System.currentTimeMillis() + SESSION_TTL_MS));
        String origin = originResolver.resolveOrigin(request);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", g.username);
        user.put("email", g.username + "@wxut.edu.cn");
        user.put("id_profile", Collections.singletonMap("name", g.name));
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("token", token);
        biz.put("authorized_url", origin + "/dsh/authorized");
        biz.put("user", user);
        log.info("dsh auth_exchange: OK uid={}", g.username);
        return envelope(biz);
    }

    @PostMapping(value = "/auth-api/v0/dsh/auth_cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authCancel(@RequestBody(required = false) Map<String, String> body) {
        return envelope(new LinkedHashMap<>());
    }

    /** 当前用户（客户端周期性校验会话；token 失效返回 code 40003 触发重新登录） */
    @GetMapping("/auth-api/v0/users/current")
    public Map<String, Object> current(HttpServletRequest request) {
        Session s = session(request);
        if (s == null) {
            return error(40003);
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", s.username);
        user.put("email", s.username + "@wxut.edu.cn");
        user.put("id_profile", Collections.singletonMap("name", s.name));
        return envelope(user);
    }

    @PostMapping("/auth-api/v0/users/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String token = request.getHeader("x-dsh-auth-token");
        if (token != null) {
            sessions.remove(token);
        }
        return envelope(new LinkedHashMap<>());
    }

    /** 钱包摘要（校园部署无计费，固定零余额） */
    @GetMapping("/api/v0/users/get_user_summary")
    public Map<String, Object> userSummary(HttpServletRequest request) {
        Session s = session(request);
        if (s == null) {
            return error(40003);
        }
        Map<String, Object> wallet = new LinkedHashMap<>();
        wallet.put("currency", "CNY");
        wallet.put("balance", "0");
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("normal_wallets", Collections.singletonList(wallet));
        biz.put("bonus_wallets", Collections.emptyList());
        return envelope(biz);
    }

    /** 通知列表（校园部署无通知渠道，空列表 + 1 小时后重轮询） */
    @GetMapping("/api/v0/users/notifications/list")
    public Map<String, Object> notifications(HttpServletRequest request) {
        if (session(request) == null) {
            return error(40003);
        }
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("next_poll_after", 3600);
        biz.put("notifications", Collections.emptyList());
        return envelope(biz);
    }

    /** 单条通知内容（空列表场景不会到达，占位实现） */
    @GetMapping("/api/v0/users/notifications/content")
    public Map<String, Object> notificationContent(HttpServletRequest request) {
        if (session(request) == null) {
            return error(40003);
        }
        return error(40003);
    }

    @PostMapping(value = "/api/v0/users/notifications/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> notificationEvent(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (session(request) == null) {
            return error(40003);
        }
        return envelope(new LinkedHashMap<>());
    }

    /** 登录完成页（客户端内嵌浏览器落地） */
    @GetMapping("/dsh/authorized")
    public void authorized(HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("<!doctype html><meta charset=\"utf-8\"><title>登录成功</title>"
                + "<p style=\"font-family:-apple-system,PingFang SC,sans-serif;font-size:15px;color:#182234\">"
                + "✓ 登录成功，请回到 XixiBuddy 客户端。</p><script>window.close()</script>");
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private Session session(HttpServletRequest request) {
        sweep();
        String token = request.getHeader("x-dsh-auth-token");
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null || s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    /** 调 CAS serviceValidate 校验 ticket，成功返回工号 */
    private String validateTicket(String ticket, String service) {
        HttpURLConnection conn = null;
        try {
            String urlStr = casConfig.getServerUrlPrefix() + "/serviceValidate"
                    + "?service=" + urlEncode(service) + "&ticket=" + urlEncode(ticket);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            StringBuilder xml = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    xml.append(line);
                }
            }
            Matcher m = CAS_USER.matcher(xml.toString());
            if (m.find()) {
                return m.group(1).trim();
            }
            log.warn("dsh authorize: CAS validation failed: {}", xml);
            return null;
        } catch (IOException e) {
            log.error("dsh authorize: CAS serviceValidate unreachable: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String displayName(SysUser user) {
        String n = user.getNickname();
        return (n == null || n.isEmpty()) ? user.getUsername() : n;
    }

    private String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.substring(0, Math.min(8, s.length()));
    }

    private static Map<String, Object> envelope(Map<String, Object> biz) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("biz_code", 0);
        data.put("biz_data", biz);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", 0);
        root.put("data", data);
        return root;
    }

    private static Map<String, Object> error(int code) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", code);
        return root;
    }

    /** 惰性清扫过期条目，避免内存滞留 */
    private void sweep() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(v -> v.expiresAt < now);
        granted.values().removeIf(v -> v.expiresAt < now);
        sessions.values().removeIf(v -> v.expiresAt < now);
    }
}
