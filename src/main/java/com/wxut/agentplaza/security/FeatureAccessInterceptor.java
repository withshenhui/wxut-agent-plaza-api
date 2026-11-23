package com.wxut.agentplaza.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.model.FeatureDef;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FeatureAccessInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OM = new ObjectMapper();
    private final FeatureService featureService;
    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        FeatureDef f = featureService.featureOfPath(req.getRequestURI());
        if (f == null) return true;                 // 未注册路径 → 放行（既有 SecurityConfig 仍兜底）
        if (!f.isEnabled()) return deny(res, 403, "该功能暂未开放");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        if ("login".equals(f.getGate()) && !loggedIn) return deny(res, 401, "请先登录");
        if ("perm".equals(f.getGate()) && f.getPermKey() != null) {
            if (!loggedIn) return deny(res, 401, "请先登录");
            Long uid = (Long) auth.getPrincipal();
            if (!permissionService.has(uid, f.getPermKey())) return deny(res, 403, "无权使用该功能，如需开通请联系管理员");
        }
        return true;
    }

    private boolean deny(HttpServletResponse res, int code, String msg) throws Exception {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("code", code); body.put("msg", msg);
        res.getWriter().write(OM.writeValueAsString(body));
        return false;
    }
}
