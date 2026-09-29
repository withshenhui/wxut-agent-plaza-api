package com.wxut.agentplaza.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CAS 过滤器 serverName 必须剥掉上下文路径：
 * cas-client 构造 service = serverName + requestURI，而 requestURI 已含 /agent-plaza，
 * serverName 再带就会拼出双前缀（/agent-plaza/agent-plaza/...），CAS 回跳 404。
 */
class SecurityConfigFilterServerNameTest {

    @Test
    void stripsContextPathFromProdUrl() {
        assertEquals("http://210.28.145.186:3001",
                SecurityConfig.filterServerName("http://210.28.145.186:3001/agent-plaza"));
    }

    @Test
    void keepsBareServerUrlUnchanged() {
        assertEquals("http://localhost:8080",
                SecurityConfig.filterServerName("http://localhost:8080"));
    }

    @Test
    void keepsHttpsSchemeAndPort() {
        assertEquals("https://ids.wxut.edu.cn:8443",
                SecurityConfig.filterServerName("https://ids.wxut.edu.cn:8443/cas"));
    }

    @Test
    void toleratesTrailingSlashOnly() {
        assertEquals("http://210.28.145.186:3001",
                SecurityConfig.filterServerName("http://210.28.145.186:3001/"));
    }
}
