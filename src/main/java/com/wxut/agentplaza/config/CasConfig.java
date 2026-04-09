package com.wxut.agentplaza.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "cas")
public class CasConfig {
    private String serverLoginUrl;
    private String serverUrlPrefix;
    private String serverLogoutUrl;
    private String clientServerUrl;
    private String frontEndUrl;
}
