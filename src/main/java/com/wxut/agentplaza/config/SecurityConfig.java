package com.wxut.agentplaza.config;

import com.wxut.agentplaza.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.jasig.cas.client.authentication.AuthenticationFilter;
import org.jasig.cas.client.util.HttpServletRequestWrapperFilter;
import org.jasig.cas.client.validation.Cas20ProxyReceivingTicketValidationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CasConfig casConfig;

    /**
     * CAS 过滤器构造 service 时用 serverName + requestURI，而 requestURI 已含上下文路径
     * （如 /agent-plaza/api/v1/sso/entry），因此 serverName 只能带 scheme://host:port，
     * 否则拼出双上下文前缀导致 CAS 登录后回跳 404、ticket 验证 service 不匹配。
     * JSON 端点（cas-login-url）不受影响，仍直接使用完整 clientServerUrl 拼接。
     */
    static String filterServerName(String clientServerUrl) {
        return clientServerUrl.replaceAll("^(https?://[^/]+).*$", "$1");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/cas-login", "/api/v1/auth/cas-login-url", "/api/v1/auth/cas-logout-url").permitAll()
                .antMatchers("/api/v1/sso/entry", "/api/v1/sso/validate").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/agents/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/models/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/configs/*").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/files/download/**").permitAll()
                .antMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .antMatchers("/", "/index.html", "/assets/**", "/images/**", "/*.js", "/*.css", "/*.png", "/*.svg", "/*.ico", "/*.html").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/agents").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/v1/agents/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/v1/agents/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/v1/categories").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/v1/categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/v1/categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/v1/models").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/v1/models/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/v1/models/**").hasRole("ADMIN")
                .antMatchers("/api/v1/users/**").hasRole("ADMIN")
                .antMatchers("/api/v1/logs/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/v1/configs/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/v1/files/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            .and()
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<AuthenticationFilter> casAuthenticationFilter() {
        FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthenticationFilter());
        registration.addUrlPatterns("/api/v1/auth/cas-login");
        registration.addUrlPatterns("/api/v1/sso/entry");
        registration.addInitParameter("casServerLoginUrl", casConfig.getServerLoginUrl());
        registration.addInitParameter("serverName", filterServerName(casConfig.getClientServerUrl()));
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<Cas20ProxyReceivingTicketValidationFilter> casValidationFilter() {
        FilterRegistrationBean<Cas20ProxyReceivingTicketValidationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Cas20ProxyReceivingTicketValidationFilter());
        registration.addUrlPatterns("/api/v1/auth/cas-login");
        registration.addUrlPatterns("/api/v1/sso/entry");
        registration.addInitParameter("casServerUrlPrefix", casConfig.getServerUrlPrefix());
        registration.addInitParameter("serverName", filterServerName(casConfig.getClientServerUrl()));
        registration.addInitParameter("redirectAfterValidation", "false");
        registration.addInitParameter("encoding", "UTF-8");
        registration.setOrder(2);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<HttpServletRequestWrapperFilter> casWrapperFilter() {
        FilterRegistrationBean<HttpServletRequestWrapperFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HttpServletRequestWrapperFilter());
        registration.addUrlPatterns("/api/v1/auth/cas-login");
        registration.addUrlPatterns("/api/v1/sso/entry");
        registration.setOrder(3);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
