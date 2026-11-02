package com.wxut.agentplaza.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "cas")
public class CasConfig {
    private String serverLoginUrl;
    private String serverUrlPrefix;
    private String serverLogoutUrl;
    private String clientServerUrl;
    private String frontEndUrl;
    /** 允许作为"请求来源"的客户端 origin 白名单（scheme://host[:port]），白名单外回退 clientServerUrl */
    private List<String> allowedClientOrigins;
}
