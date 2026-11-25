package com.wxut.agentplaza.service.wecom;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WecomUserResolverTest {

    @Test
    void 直连命中优先返回工号() {
        // userid=工号：direct 查 sys_user 命中
        WecomUserResolver.Lookup direct = (k) -> "2023091330".equals(k) ? k : null;
        WecomUserResolver.Lookup mapped = (k) -> null;
        assertEquals("2023091330", WecomUserResolver.resolveUsername("2023091330", direct, mapped));
    }

    @Test
    void 直连未命中回落映射表() {
        WecomUserResolver.Lookup direct = (k) -> null;
        WecomUserResolver.Lookup mapped = (k) -> Map.of("zhangsan", "2013070832").get(k);
        assertEquals("2013070832", WecomUserResolver.resolveUsername("zhangsan", direct, mapped));
    }

    @Test
    void 均未命中返回null降游客() {
        WecomUserResolver.Lookup none = (k) -> null;
        assertNull(WecomUserResolver.resolveUsername("nobody", none, none));
    }

    @Test
    void 空userid返回null() {
        WecomUserResolver.Lookup none = (k) -> null;
        assertNull(WecomUserResolver.resolveUsername("", none, none));
        assertNull(WecomUserResolver.resolveUsername(null, none, none));
    }
}
