# CAS SSO 集成实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为前台用户集成学校 CAS 统一身份认证，认证成功后签发 JWT，前端无缝接入。

**Architecture:** CAS 只负责首次身份验证，认证通过后后端自动创建/关联本地用户并签发 JWT，前端通过回调页面接收 token 并存入 localStorage。后台管理员认证完全不受影响。

**Tech Stack:** Spring Boot 2.7.18 + cas-client-core 3.2.1 + JWT + React 18 + Ant Design

---

## Task 1: 后端 — 添加 CAS 依赖

**Files:**
- Modify: `wxut-agent-pg-api/pom.xml`

- [ ] **Step 1: 在 pom.xml 中添加 cas-client-core 依赖**

在 `pom.xml` 的 `<dependencies>` 节点中，`hutool-all` 依赖之后添加：

```xml
<dependency>
    <groupId>org.jasig.cas.client</groupId>
    <artifactId>cas-client-core</artifactId>
    <version>3.2.1</version>
</dependency>
```

- [ ] **Step 2: 验证依赖能正常解析**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn dependency:resolve -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add cas-client-core dependency for SSO integration"
```

---

## Task 2: 后端 — 添加 CAS 配置

**Files:**
- Modify: `wxut-agent-pg-api/src/main/resources/application.yml`
- Create: `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/config/CasConfig.java`

- [ ] **Step 1: 在 application.yml 末尾添加 CAS 配置项**

在 `application.yml` 的 `jwt` 节之后添加：

```yaml
cas:
  server-login-url: https://ids.wxit.edu.cn/authserver/login
  server-url-prefix: https://ids.wxit.edu.cn/authserver
  client-server-url: http://210.28.145.186:8080
  front-end-url: http://210.28.145.186:3001
```

注意：`front-end-url` 在部署时需与前端实际地址和端口保持一致。

- [ ] **Step 2: 创建 CasConfig.java 配置 Bean**

创建 `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/config/CasConfig.java`：

```java
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
    private String clientServerUrl;
    private String frontEndUrl;
}
```

- [ ] **Step 3: 验证编译通过**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/wxut/agentplaza/config/CasConfig.java
git commit -m "feat: add CAS configuration properties and config bean"
```

---

## Task 3: 后端 — 添加 findOrCreateCasUser 到 AuthService

**Files:**
- Modify: `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/service/AuthService.java`
- Modify: `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/service/impl/AuthServiceImpl.java`

- [ ] **Step 1: 在 AuthService 接口中添加方法签名**

在 `AuthService.java` 中添加：

```java
SysUser findOrCreateCasUser(String uid, String cn);
```

完整的 `AuthService.java`：

```java
package com.wxut.agentplaza.service;

import com.wxut.agentplaza.dto.LoginDTO;
import com.wxut.agentplaza.dto.RegisterDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.vo.LoginVO;
import com.wxut.agentplaza.vo.UserInfoVO;

public interface AuthService {
    LoginVO login(LoginDTO dto);
    LoginVO register(RegisterDTO dto);
    UserInfoVO getCurrentUser(Long userId);
    SysUser findOrCreateCasUser(String uid, String cn);
}
```

- [ ] **Step 2: 在 AuthServiceImpl 中实现 findOrCreateCasUser**

在 `AuthServiceImpl.java` 中添加方法：

```java
@Override
public SysUser findOrCreateCasUser(String uid, String cn) {
    SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, uid));
    if (user != null) {
        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        return user;
    }
    user = new SysUser();
    user.setUsername(uid);
    user.setNickname(cn != null ? cn : uid);
    user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
    user.setRole("user");
    user.setStatus(1);
    userMapper.insert(user);
    return user;
}
```

注意：需要在 `AuthServiceImpl.java` 顶部已有 `import com.wxut.agentplaza.entity.SysUser;`（已有，因为 SysUser 在现有代码中被使用）。

- [ ] **Step 3: 验证编译通过**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wxut/agentplaza/service/AuthService.java src/main/java/com/wxut/agentplaza/service/impl/AuthServiceImpl.java
git commit -m "feat: add findOrCreateCasUser for CAS auto-registration"
```

---

## Task 4: 后端 — 创建 CasAuthController

**Files:**
- Create: `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/controller/CasAuthController.java`

- [ ] **Step 1: 创建 CasAuthController**

创建 `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/controller/CasAuthController.java`：

```java
package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class CasAuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final CasConfig casConfig;

    @GetMapping("/cas-login")
    public void casLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uid = request.getRemoteUser();
        if (uid == null || uid.isEmpty()) {
            response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=auth_failed");
            return;
        }

        try {
            Principal principal = request.getUserPrincipal();
            String cn = uid;
            if (principal instanceof AttributePrincipal) {
                AttributePrincipal aPrincipal = (AttributePrincipal) principal;
                java.util.Map<String, Object> attrs = aPrincipal.getAttributes();
                Object cnAttr = attrs.get("cn");
                if (cnAttr != null) {
                    cn = cnAttr.toString();
                }
            }

            SysUser user = authService.findOrCreateCasUser(uid, cn);
            String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
            response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?token=" + token);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (errorMsg.contains("禁用")) {
                response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=user_disabled");
            } else {
                response.sendRedirect(casConfig.getFrontEndUrl() + "/#/cas-callback?error=auth_failed");
            }
        }
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wxut/agentplaza/controller/CasAuthController.java
git commit -m "feat: add CasAuthController for CAS authentication callback"
```

---

## Task 5: 后端 — 注册 CAS Filter 并放行路由

**Files:**
- Modify: `wxut-agent-pg-api/src/main/java/com/wxut/agentplaza/config/SecurityConfig.java`

- [ ] **Step 1: 修改 SecurityConfig，添加 CAS Filter 注册和路由放行**

将 `SecurityConfig.java` 完整替换为：

```java
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/cas-login").permitAll()
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
    public FilterRegistrationBean<AuthenticationFilter> casAuthenticationFilter() {
        FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthenticationFilter());
        registration.addUrlPatterns("/api/v1/auth/cas-login");
        registration.addInitParameter("casServerLoginUrl", casConfig.getServerLoginUrl());
        registration.addInitParameter("serverName", casConfig.getClientServerUrl());
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<Cas20ProxyReceivingTicketValidationFilter> casValidationFilter() {
        FilterRegistrationBean<Cas20ProxyReceivingTicketValidationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Cas20ProxyReceivingTicketValidationFilter());
        registration.addUrlPatterns("/api/v1/auth/cas-login");
        registration.addInitParameter("casServerUrlPrefix", casConfig.getServerUrlPrefix());
        registration.addInitParameter("serverName", casConfig.getClientServerUrl());
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
```

关键变更说明：
- 构造器注入 `CasConfig`
- `permitAll()` 中新增 `/api/v1/auth/cas-login`
- 新增三个 `FilterRegistrationBean`，仅拦截 `/api/v1/auth/cas-login`

- [ ] **Step 2: 验证编译通过**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wxut/agentplaza/config/SecurityConfig.java
git commit -m "feat: register CAS filters and permit cas-login endpoint"
```

---

## Task 6: 后端 — 编译验证完整后端

- [ ] **Step 1: 完整编译打包**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn clean compile -q`
Expected: BUILD SUCCESS

如果有编译错误，根据错误信息修复后再编译。

- [ ] **Step 2: Commit（如有修复）**

只有修复了编译错误才需要此步骤。

---

## Task 7: 前端 — 创建 userRequest 和 UserContext

**Files:**
- Create: `wxut-agent-pg/src/api/userRequest.js`
- Create: `wxut-agent-pg/src/api/userAuth.js`
- Create: `wxut-agent-pg/src/UserContext.jsx`

- [ ] **Step 1: 创建 userRequest.js（前台用户专用 axios 实例）**

创建 `wxut-agent-pg/src/api/userRequest.js`：

```javascript
import axios from 'axios';

const apiBase = import.meta.env.PROD
  ? `${import.meta.env.VITE_API_BASE_URL || ''}/api/v1`
  : '/api/v1';

const userRequest = axios.create({
  baseURL: apiBase,
  timeout: 10000,
});

userRequest.interceptors.request.use((config) => {
  const token = localStorage.getItem('user_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

userRequest.interceptors.response.use(
  (res) => {
    const { code, message, data } = res.data;
    if (code === 200) return data;
    return Promise.reject(new Error(message || '请求失败'));
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('user_token');
    }
    const msg = err.response?.data?.message || err.message;
    return Promise.reject(new Error(msg));
  },
);

export default userRequest;
```

- [ ] **Step 2: 创建 userAuth.js（前台用户认证 API）**

创建 `wxut-agent-pg/src/api/userAuth.js`：

```javascript
import userRequest from './userRequest';

export function getUserInfo() {
  return userRequest.get('/auth/info');
}
```

- [ ] **Step 3: 创建 UserContext.jsx（前台用户状态管理）**

创建 `wxut-agent-pg/src/UserContext.jsx`：

```javascript
import React, { createContext, useContext, useState, useEffect } from 'react';
import { getUserInfo } from './api/userAuth';

const UserContext = createContext(null);

export function UserProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem('user_token');
    if (token) {
      getUserInfo()
        .then((info) => setUser(info))
        .catch(() => localStorage.removeItem('user_token'))
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, []);

  const casLogin = () => {
    const backendUrl = import.meta.env.PROD
      ? import.meta.env.VITE_API_BASE_URL || ''
      : '';
    window.location.href = `${backendUrl}/api/v1/auth/cas-login`;
  };

  const logout = () => {
    localStorage.removeItem('user_token');
    setUser(null);
  };

  return (
    <UserContext.Provider value={{ user, setUser, casLogin, logout, loading }}>
      {children}
    </UserContext.Provider>
  );
}

export function useUser() {
  const ctx = useContext(UserContext);
  if (!ctx) throw new Error('useUser must be used within UserProvider');
  return ctx;
}
```

- [ ] **Step 4: Commit**

```bash
git add src/api/userRequest.js src/api/userAuth.js src/UserContext.jsx
git commit -m "feat: add user request client and UserContext for CAS auth"
```

---

## Task 8: 前端 — 创建 CasCallback 页面并注册路由

**Files:**
- Create: `wxut-agent-pg/src/CasCallback.jsx`
- Modify: `wxut-agent-pg/src/App.jsx`

- [ ] **Step 1: 创建 CasCallback.jsx**

创建 `wxut-agent-pg/src/CasCallback.jsx`：

```javascript
import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spin, Result } from 'antd';
import { getUserInfo } from './api/userAuth';

export default function CasCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    const hash = window.location.hash;
    const params = new URLSearchParams(hash.split('?')[1] || '');
    const token = params.get('token');
    const error = params.get('error');

    if (error) {
      return;
    }

    if (token) {
      localStorage.setItem('user_token', token);
      getUserInfo()
        .then(() => {
          navigate('/', { replace: true });
        })
        .catch(() => {
          localStorage.removeItem('user_token');
          navigate('/', { replace: true });
        });
    } else {
      navigate('/', { replace: true });
    }
  }, [navigate]);

  const hash = window.location.hash;
  const params = new URLSearchParams(hash.split('?')[1] || '');
  const error = params.get('error');

  if (error === 'auth_failed') {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Result
          status="error"
          title="认证失败"
          subTitle="统一身份认证未通过，请重试"
          extra={<a href="#/">返回首页</a>}
        />
      </div>
    );
  }

  if (error === 'user_disabled') {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Result
          status="warning"
          title="账号已禁用"
          subTitle="您的账号已被管理员禁用，请联系管理员"
          extra={<a href="#/">返回首页</a>}
        />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
      <Spin size="large" tip="正在登录..." />
    </div>
  );
}
```

- [ ] **Step 2: 在 App.jsx 中添加路由和 UserProvider**

在 `App.jsx` 的 import 区域添加：

```javascript
import CasCallback from './CasCallback';
import { UserProvider } from './UserContext';
```

将 `App` 函数中的 `<Router>` 内 `<Routes>` 的前台路由部分，在第一个 Route 之前添加 `/cas-callback` 路由。用 `<UserProvider>` 包裹前台路由：

将 `App` 函数修改为：

```javascript
function App() {
  return (
    <SiteConfigProvider>
    <Router>
      <UserProvider>
      <Routes>
        {/* 前台页面 */}
        <Route path="/" element={<Home />} />
        <Route path="/cas-callback" element={<CasCallback />} />
        <Route path="/chat/:id" element={<ChatPage />} />
        <Route path="/campus-planning" element={<DigitalCampusPlanning />} />
        <Route path="/about" element={<AboutUs />} />
        <Route path="/model-management" element={<ModelManagement />} />
        <Route path="/openclaw" element={<OpenClaw />} />
        <Route path="/guide" element={<UnderConstruction pageType="campus" />} />
        <Route path="/manual" element={<UnderConstruction pageType="manual" />} />

        {/* 管理后台 */}
        <Route path="/admin/login" element={<AdminLoginRoute />} />
        <Route path="/admin" element={<AdminRoute />}>
          <Route index element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="agents" element={<AgentManagePage />} />
          <Route path="categories" element={<CategoryManagePage />} />
          <Route path="models" element={<ModelManagePage />} />
          <Route path="users" element={<UserManagePage />} />
          <Route path="configs" element={<ConfigManagePage />} />
          <Route path="files" element={<FileManagePage />} />
          <Route path="logs" element={<LogManagePage />} />
        </Route>
      </Routes>
      </UserProvider>
    </Router>
    </SiteConfigProvider>
  );
}
```

- [ ] **Step 3: Commit**

```bash
git add src/CasCallback.jsx src/App.jsx
git commit -m "feat: add CasCallback page and register route"
```

---

## Task 9: 前端 — 在 PageLayout 中添加 CAS 登录按钮

**Files:**
- Modify: `wxut-agent-pg/src/components/PageLayout.jsx`

- [ ] **Step 1: 修改 PageLayout.jsx，替换硬编码的"管理员"为动态用户状态**

将 `PageLayout.jsx` 的 header-right 区域替换为根据用户登录状态显示不同内容：

```javascript
import React from 'react';
import Sidebar from './Sidebar';
import { useSiteConfig } from './SiteConfigContext';
import { useUser } from '../UserContext';

function PageLayout({ children, title, onBack, isSidebarCollapsed, setIsSidebarCollapsed, activePath, footerText }) {
  const { siteFooter } = useSiteConfig();
  const { user, casLogin, logout } = useUser();

  return (
    <div className="el-layout">
      <Sidebar
        isCollapsed={isSidebarCollapsed}
        onToggle={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
        activePath={activePath}
      />
      <div className="el-main-container">
        <header className="el-header">
          <div style={{ display: 'flex', alignItems: 'center' }}>
            {onBack && (
              <button className="el-header-back" onClick={onBack}>
                ‹
              </button>
            )}
            <h1 className="el-header-title">{title}</h1>
          </div>
          <div className="el-header-right">
            {user ? (
              <div className="el-header-user">
                <div className="el-header-avatar">{user.nickname ? user.nickname[0] : '用'}</div>
                <span className="el-header-username">{user.nickname || user.username}</span>
                <a href="#" onClick={(e) => { e.preventDefault(); logout(); }} style={{ marginLeft: 12, fontSize: 13, color: '#999' }}>退出</a>
              </div>
            ) : (
              <div className="el-header-user">
                <button
                  onClick={casLogin}
                  style={{
                    background: '#1890ff',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 6,
                    padding: '6px 16px',
                    cursor: 'pointer',
                    fontSize: 14,
                  }}
                >
                  统一身份认证登录
                </button>
              </div>
            )}
          </div>
        </header>
        <div className="el-content">
          {children}
        </div>
        <footer className="el-footer">
          <p>{footerText || siteFooter}</p>
        </footer>
      </div>
    </div>
  );
}

export default PageLayout;
```

- [ ] **Step 2: 验证前端编译**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg && npx vite build --mode development 2>&1 | tail -5`
Expected: 构建成功，无错误

- [ ] **Step 3: Commit**

```bash
git add src/components/PageLayout.jsx
git commit -m "feat: add CAS login button and dynamic user display in PageLayout header"
```

---

## Task 10: 前后端联调验证

- [ ] **Step 1: 启动后端**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg-api && mvn spring-boot:run`

验证：后端在 8080 端口启动成功。

- [ ] **Step 2: 启动前端**

Run: `cd d:/WorkMints/vscode/wxut-agent-pg && npx vite`

验证：前端在开发服务器启动成功。

- [ ] **Step 3: 手动验证 CAS 登录流程**

1. 访问前端首页，确认 header 区域出现"统一身份认证登录"按钮
2. 点击按钮，确认浏览器跳转到后端 `/api/v1/auth/cas-login`
3. 确认后端 CAS Filter 重定向到学校 CAS 登录页 `https://ids.wxit.edu.cn/authserver/login`
4. 在 CAS 页面输入学号密码完成认证（需要有效的学校账号）
5. 确认 CAS 回调到后端，后端重定向到前端 `/#/cas-callback?token=xxx`
6. 确认 CasCallback 页面存储 token 并跳转首页
7. 确认首页 header 显示用户昵称和"退出"按钮
8. 点击"退出"，确认回到未登录状态
9. 确认后台管理 `/admin/login` 的登录流程不受影响

- [ ] **Step 4: Commit（如有联调修复）**

只有修复了联调问题才需要此步骤。
