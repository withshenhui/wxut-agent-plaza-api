package com.wxut.agentplaza.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CasOriginResolver：双入口（域名 aicenter / IP 210.28.145.186:3001）各自闭环——
 * "IP 进回 IP，域名进回域名"；白名单外 Host 回退配置域名（防伪造 Host 污染
 * 携带 token 的重定向）。
 */
class CasOriginResolverTest {

    private CasConfig config(String clientServerUrl, String... allowed) {
        CasConfig cfg = new CasConfig();
        cfg.setClientServerUrl(clientServerUrl);
        cfg.setAllowedClientOrigins(Arrays.asList(allowed));
        return cfg;
    }

    private MockHttpServletRequest request(String host, String forwardedProto) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (host != null) req.addHeader("Host", host);
        if (forwardedProto != null) req.addHeader("X-Forwarded-Proto", forwardedProto);
        req.setServerName(host == null ? "unknown" : host.split(":")[0]);
        req.setScheme(forwardedProto != null ? "http" : "http");
        return req;
    }

    @Test
    void domainRequestStaysOnDomain() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("https://aicenter.wxut.edu.cn/agent-plaza",
                        "https://aicenter.wxut.edu.cn", "http://210.28.145.186:3001"));
        assertEquals("https://aicenter.wxut.edu.cn",
                resolver.resolveOrigin(request("aicenter.wxut.edu.cn", "https")));
    }

    @Test
    void directIpRequestStaysOnIp() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("https://aicenter.wxut.edu.cn/agent-plaza",
                        "https://aicenter.wxut.edu.cn", "http://210.28.145.186:3001"));
        // IP 直连 Tomcat：无 X-Forwarded-Proto，scheme=http
        assertEquals("http://210.28.145.186:3001",
                resolver.resolveOrigin(request("210.28.145.186:3001", null)));
    }

    @Test
    void unknownHostFallsBackToConfiguredDomain() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("https://aicenter.wxut.edu.cn/agent-plaza",
                        "https://aicenter.wxut.edu.cn", "http://210.28.145.186:3001"));
        assertEquals("https://aicenter.wxut.edu.cn",
                resolver.resolveOrigin(request("evil.example.com", "https")));
    }

    @Test
    void serverBaseAppendsWarContextPath() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("https://aicenter.wxut.edu.cn/agent-plaza",
                        "https://aicenter.wxut.edu.cn", "http://210.28.145.186:3001"));
        MockHttpServletRequest req = request("210.28.145.186:3001", null);
        req.setRequestURI("/agent-plaza/api/v1/sso/entry");
        req.setContextPath("/agent-plaza");
        req.setQueryString("app=plaza-admin");
        assertEquals("http://210.28.145.186:3001/agent-plaza", resolver.resolveServerBase(req));
        assertEquals("http://210.28.145.186:3001/agent-plaza/api/v1/sso/entry?app=plaza-admin",
                resolver.resolveServiceUrl(req));
    }

    @Test
    void serviceUrlStripsTicketParamOnly() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("https://aicenter.wxut.edu.cn/agent-plaza",
                        "https://aicenter.wxut.edu.cn", "http://210.28.145.186:3001"));
        MockHttpServletRequest req = request("aicenter.wxut.edu.cn", "https");
        req.setRequestURI("/agent-plaza/api/v1/sso/entry");
        req.setContextPath("/agent-plaza");
        req.setQueryString("app=plaza-admin&ticket=ST-12345&redirect=%2F");
        assertEquals("https://aicenter.wxut.edu.cn/agent-plaza/api/v1/sso/entry?app=plaza-admin&redirect=%2F",
                resolver.resolveServiceUrl(req));
    }

    @Test
    void localhostAllowedWhenListed() {
        CasOriginResolver resolver = new CasOriginResolver(
                config("http://210.28.145.186:3001", "http://localhost:3001"));
        assertEquals("http://localhost:3001", resolver.resolveOrigin(request("localhost:3001", null)));
    }
}
