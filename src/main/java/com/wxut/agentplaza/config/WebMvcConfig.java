package com.wxut.agentplaza.config;

import com.wxut.agentplaza.security.FeatureAccessInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    private final FeatureAccessInterceptor featureAccessInterceptor;

    public WebMvcConfig(FeatureAccessInterceptor featureAccessInterceptor) {
        this.featureAccessInterceptor = featureAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(featureAccessInterceptor)
                .addPathPatterns("/api/v1/**")
                // /api/v1/kb/search 与 /api/v1/kb/public-catalog 是 token 网关（X-Kb-Token）校验的公开端点，
                // 供 XixiBuddy / 跨服务检索匿名调用，SecurityConfig 已 permitAll，必须保持匿名放行，
                // 不能被本拦截器的 kb 功能门（gate=login）拦下。
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/configs/**", "/api/v1/sso/**", "/api/v1/colleges/**",
                        "/api/v1/kb/search", "/api/v1/kb/public-catalog");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}
