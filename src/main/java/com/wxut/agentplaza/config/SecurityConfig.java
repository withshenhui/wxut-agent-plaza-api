package com.wxut.agentplaza.config;

import com.wxut.agentplaza.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/agents/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/models/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/configs/*").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/files/download/**").permitAll()
                .antMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
