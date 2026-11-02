package com.wxut.agentplaza.security;

import com.wxut.agentplaza.config.CasConfig;
import com.wxut.agentplaza.config.CasOriginResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasig.cas.client.util.AbstractCasFilter;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.Cas20ServiceTicketValidator;
import org.jasig.cas.client.validation.TicketValidationException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 替代 cas-client 3.2.1 Cas20ProxyReceivingTicketValidationFilter（本项目未用代理票据）：
 * 命中 ticket 参数时向学校 CAS 校验。service 用与 DynamicCasAuthFilter 同一套
 * 动态构造（CasOriginResolver.resolveServiceUrl），保证两端口径一致——不一致 CAS
 * 会判 ticket 无效。校验通过把 Assertion 放进 session（原版同款键
 * _const_cas_assertion_），后续 HttpServletRequestWrapperFilter 照常把 principal
 * 暴露给控制器（getRemoteUser / AttributePrincipal）。
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicCasValidationFilter implements Filter {

    private final CasConfig casConfig;
    private final CasOriginResolver originResolver;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String ticket = request.getParameter("ticket");
        boolean hasTicket = ticket != null && !ticket.isEmpty();
        boolean callbackError = request.getParameter("error") != null
                || request.getParameter("renew") != null;
        if (!"GET".equals(request.getMethod()) || !hasTicket || callbackError) {
            chain.doFilter(request, response);
            return;
        }

        String service = originResolver.resolveServiceUrl(request);
        try {
            Cas20ServiceTicketValidator validator =
                    new Cas20ServiceTicketValidator(casConfig.getServerUrlPrefix());
            validator.setEncoding("UTF-8");
            Assertion assertion = validator.validate(ticket, service);
            request.getSession(true).setAttribute(AbstractCasFilter.CONST_CAS_ASSERTION, assertion);
            log.info("CAS ticket validated: service={}", service);
        } catch (TicketValidationException e) {
            log.warn("CAS ticket validation failed: service={}, reason={}", service, e.getMessage());
            response.sendError(401, "CAS ticket 校验失败");
            return;
        }
        // redirectAfterValidation=false（与原配置一致）：不重定向，直接进控制器
        chain.doFilter(request, response);
    }
}
