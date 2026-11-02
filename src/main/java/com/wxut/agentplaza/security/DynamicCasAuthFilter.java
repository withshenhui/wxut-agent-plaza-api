package com.wxut.agentplaza.security;

import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.config.CasOriginResolver;
import lombok.RequiredArgsConstructor;
import org.jasig.cas.client.util.AbstractCasFilter;
import org.jasig.cas.client.validation.Assertion;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;

/**
 * 替代 cas-client 3.2.1 AuthenticationFilter：未持有 CAS 会话时把用户 302 到学校
 * CAS 登录页。service 按"请求来源"动态生成（域名进拼域名、IP 进拼 IP），替代原版
 * 启动时固定的 serverName init-param——原版做不到双入口各自闭环。
 *
 * 行为对齐 3.2.1 原版：session 里已有 assertion 或请求带 ticket 参数（回调瞬间，
 * 交给 DynamicCasValidationFilter 校验）都放行，否则重定向 CAS 登录页。
 */
@RequiredArgsConstructor
public class DynamicCasAuthFilter implements Filter {

    private final CasConfig casConfig;
    private final CasOriginResolver originResolver;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        HttpSession session = request.getSession(false);
        Assertion assertion = session == null ? null
                : (Assertion) session.getAttribute(AbstractCasFilter.CONST_CAS_ASSERTION);
        if (assertion != null) {
            chain.doFilter(request, response);
            return;
        }
        String ticket = request.getParameter("ticket");
        if (ticket != null && !ticket.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String service = originResolver.resolveServiceUrl(request);
        String loginUrl = casConfig.getServerLoginUrl()
                + "?service=" + URLEncoder.encode(service, "UTF-8");
        response.sendRedirect(loginUrl);
    }
}
