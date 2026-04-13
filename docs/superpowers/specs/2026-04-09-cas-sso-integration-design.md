# CAS SSO 集成设计

## 背景

wxut-agent-pg（前端）和 wxut-agent-pg-api（后端）需要对接无锡职业技术大学的统一身份认证平台（CAS），为前台用户提供单点登录功能。后台管理员认证保持现有 JWT 方式不变。

## 目标

- 前台用户通过学校 CAS 统一认证登录
- CAS 认证成功后自动创建/关联本地用户，签发 JWT
- 不改动后台管理员的登录流程

## 架构

### 认证流程

```
用户点击"统一身份认证登录"
       │
       ▼
浏览器跳转 → 后端 GET /api/v1/auth/cas-login
       │
       ▼
CAS Authentication Filter 拦截 → 重定向到学校 CAS 登录页
       │
       ▼
用户在 CAS 输入学号/密码完成认证
       │
       ▼
CAS 带 ticket 回调 → 后端 GET /api/v1/auth/cas-login?ticket=xxx
       │
       ▼
CAS Validation Filter 校验 ticket → 通过
       │
       ▼
CasAuthController 处理:
  ├─ request.getRemoteUser() → uid
  ├─ AttributePrincipal.getAttributes() → cn
  ├─ findOrCreateCasUser(uid, cn) → 查找或创建用户
  └─ JwtTokenProvider.generateToken() → 签发 JWT
       │
       ▼
302 重定向 → 前端 /cas-callback?token=xxx
       │
       ▼
前端 CasCallback 页面:
  ├─ localStorage 存入 token
  ├─ 调用 /auth/info 获取用户信息
  └─ 跳转首页
```

### 关键设计决策

1. **CAS 只负责身份验证**：CAS 仅在首次登录时介入，认证通过后立刻转换为 JWT 体系
2. **前后端分离兼容**：通过 302 重定向将 token 传递给前端，避免跨域 cookie 问题
3. **用户自动创建**：首次 CAS 登录时自动在 sys_user 表中创建对应账号

### 错误处理

- CAS ticket 校验失败 → 重定向到前端 `/cas-callback?error=auth_failed`
- 用户被禁用（status=0）→ 重定向到前端 `/cas-callback?error=user_disabled`

## 后端变更

### 1. pom.xml — 新增依赖

```xml
<dependency>
    <groupId>org.jasig.cas.client</groupId>
    <artifactId>cas-client-core</artifactId>
    <version>3.2.1</version>
</dependency>
```

### 2. application.yml — 新增配置

```yaml
cas:
  server-login-url: https://ids.wxit.edu.cn/authserver/login
  server-url-prefix: https://ids.wxit.edu.cn/authserver
  client-server-url: http://210.28.145.186:8080
  front-end-url: http://210.28.145.186:3001  # 部署时与前端实际地址保持一致
```

### 3. config/CasConfig.java — 新增

读取 cas 配置项的 Configuration Bean，提供 getter 方法供 Filter 注册时使用。

### 4. config/SecurityConfig.java — 修改

在 filterChain 中注册三个 CAS Filter，仅拦截 `/api/v1/auth/cas-login`：

1. `AuthenticationFilter` — 未认证时重定向到 CAS
2. `Cas30ProxyReceivingTicketValidationFilter` — 校验 ticket
3. `HttpServletRequestWrapperFilter` — 包装 request

注册顺序：在 `UsernamePasswordAuthenticationFilter` 之前。

### 5. controller/CasAuthController.java — 新增

- `GET /api/v1/auth/cas-login`：CAS 认证成功回调入口
- 从 request 获取 CAS uid 和 cn 属性
- 调用 AuthService.findOrCreateCasUser(uid, cn)
- 签发 JWT token
- 302 重定向到 `{front-end-url}/cas-callback?token={jwt}`
- 异常情况重定向到 `{front-end-url}/cas-callback?error={errorCode}`

### 6. AuthService — 修改

接口新增方法：

```java
SysUser findOrCreateCasUser(String uid, String cn);
```

实现逻辑：
- 按 username 查询 sys_user 表
- 存在且 status=1 → 返回用户
- 存在且 status=0 → 抛出用户禁用异常
- 不存在 → 创建新用户（username=uid, nickname=cn, password=随机UUID加密, role=user）

### 7. SecurityConfig 路由放行

`/api/v1/auth/cas-login` 需要在 `permitAll()` 中放行，由 CAS Filter 自行处理认证。

## 前端变更

### 1. src/CasCallback.jsx — 新增

CAS 认证回调页面：
- 从 URL 参数提取 token 或 error
- token 存入 localStorage
- 调用后端获取用户信息
- 成功后跳转首页
- 失败时显示错误提示

### 2. src/UserContext.jsx — 新增

前台用户 Context，类似 AdminContext：
- 管理 user 状态和 token
- 提供 login（CAS 方式由 CasCallback 处理）、logout 方法
- logout 时清除 localStorage 并跳转

### 3. src/App.jsx — 修改

新增路由：
- `/cas-callback` → CasCallback 组件

### 4. 导航栏/首页组件 — 修改

未登录状态下新增"统一身份认证登录"按钮，点击后跳转到后端 CAS 入口：

```javascript
window.location.href = `${backendUrl}/api/v1/auth/cas-login`;
```

### 5. 不改动的部分

- 后台管理员登录流程（AdminContext、AdminGuard、LoginPage）完全不变
- 前端现有路由结构不变

## 涉及文件汇总

| 项目 | 文件 | 操作 |
|------|------|------|
| 后端 | pom.xml | 改：加 cas-client-core 依赖 |
| 后端 | application.yml | 改：加 cas 配置项 |
| 后端 | config/CasConfig.java | 新增 |
| 后端 | config/SecurityConfig.java | 改：注册 CAS Filter + 放行路由 |
| 后端 | controller/CasAuthController.java | 新增 |
| 后端 | service/AuthService.java | 改：加 findOrCreateCasUser |
| 后端 | service/impl/AuthServiceImpl.java | 改：实现 findOrCreateCasUser |
| 前端 | src/CasCallback.jsx | 新增 |
| 前端 | src/UserContext.jsx | 新增 |
| 前端 | src/App.jsx | 改：加 /cas-callback 路由 |
| 前端 | 导航栏/首页组件 | 改：加 CAS 登录按钮 |
