package com.wxut.agentplaza.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {
    /** 与锡锡平台约定的服务间共享密钥 */
    private String secret;
    /** 各接入方回跳地址，key = entry 的 app 参数值 */
    private java.util.Map<String, String> callbacks = new java.util.HashMap<>();
}
