package com.wxut.agentplaza.service.wecom;

/** 企微 UserId → 中台 username 解析（纯逻辑，注入查库函数便于测试）。
 *  ① UserId 直接当 username 查 sys_user；② 未命中查映射表；③ 都没有返回 null（调用方降游客）。 */
public final class WecomUserResolver {

    public interface Lookup { String apply(String key); }

    private WecomUserResolver() { }

    public static String resolveUsername(String wecomUserId, Lookup direct, Lookup mapped) {
        if (wecomUserId == null || wecomUserId.isEmpty()) return null;
        String hit = direct.apply(wecomUserId);
        if (hit != null && !hit.isEmpty()) return hit;
        if (mapped != null) {
            String m = mapped.apply(wecomUserId);
            if (m != null && !m.isEmpty()) return m;
        }
        return null;
    }
}
