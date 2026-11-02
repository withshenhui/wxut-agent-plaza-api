package com.wxut.agentplaza.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按请求来源动态解析客户端可达地址（替代写死的 cas.client-server-url / front-end-url）。
 *
 * 背景：AI 中台有两个入口——https://aicenter.wxut.edu.cn（域名，经 nginx 反代，
 * Host 保留为域名且带 X-Forwarded-Proto: https）与 http://210.28.145.186:3001/agent-plaza/
 * （IP 直连 Tomcat，Host 即 IP:端口）。CAS 登录回调（service）与前端回跳地址若写死
 * 任一方，从另一方入口登录的用户会被"汇流"到写死的那边。这里依据请求 Host 在白名单
 * 内动态选择，两个入口各自闭环："IP 进、登录后仍回 IP；域名进、登录后仍回域名"。
 *
 * 白名单外的 Host（伪造/未登记）一律回退 cas.client-server-url 的 origin，
 * 防止恶意 Host 污染携带 token 的重定向目标。
 */
@Component
@RequiredArgsConstructor
public class CasOriginResolver {

    private final CasConfig casConfig;

    /** 请求对应的客户端 origin（scheme://host[:port]），白名单外回退配置值 */
    public String resolveOrigin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null && !host.isEmpty()) {
            String proto = request.getHeader("X-Forwarded-Proto");
            if (proto == null || proto.isEmpty() || !proto.matches("[a-zA-Z]+")) {
                proto = request.getScheme();
            }
            String origin = (proto.toLowerCase(Locale.ROOT) + "://" + host).toLowerCase(Locale.ROOT);
            if (allowedOrigins().contains(origin)) {
                return origin;
            }
        }
        return fallbackOrigin();
    }

    /**
     * 后端服务可达前缀：origin + contextPath。
     * WAR 部署时 contextPath=/agent-plaza，本地 jar 运行为空串，两种形态都正确。
     */
    public String resolveServerBase(HttpServletRequest request) {
        return resolveOrigin(request) + request.getContextPath();
    }

    /** 前端 SPA 可达前缀：与后端服务同源同 context（域名根路径与 /agent-plaza/ 均可达） */
    public String resolveFrontEndBase(HttpServletRequest request) {
        return resolveServerBase(request);
    }

    /**
     * 完整 CAS service URL：origin + requestURI（含 context path）+ 去掉 ticket 参数的
     * 原查询串。DynamicCasAuthFilter（发给 CAS 登录页）与 DynamicCasValidationFilter
     * （提交 CAS 校验）共用此方法，保证两端 service 完全一致——不一致 CAS 会判
     * ticket 无效。
     */
    public String resolveServiceUrl(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder(resolveOrigin(request)).append(request.getRequestURI());
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            String cleaned = Arrays.stream(query.split("&"))
                    .filter(p -> !p.isEmpty() && !"ticket".equals(p) && !p.startsWith("ticket="))
                    .collect(Collectors.joining("&"));
            if (!cleaned.isEmpty()) {
                sb.append('?').append(cleaned);
            }
        }
        return sb.toString();
    }

    private Set<String> allowedOrigins() {
        Set<String> set = new HashSet<>();
        for (String o : safeList(casConfig.getAllowedClientOrigins())) {
            set.add(normalize(o));
        }
        set.add(fallbackOrigin());
        return set;
    }

    private String fallbackOrigin() {
        String url = casConfig.getClientServerUrl();
        if (url == null || url.isEmpty()) {
            return "";
        }
        return normalize(url.replaceAll("^(https?://[^/]+).*$", "$1"));
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static String normalize(String origin) {
        return origin.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
    }
}
