package com.wxut.agentplaza.service;

import com.wxut.agentplaza.service.impl.FeatureServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 迁移等价：新链路角色默认集 = 旧写死集合（此处直接以常量复刻旧值比对） */
class PermissionDefaultsEquivalenceTest {

    @Test
    void user_default_equals_old_USER_DEFAULT() {
        Set<String> old = new HashSet<>(Arrays.asList(
            "plaza.view","chat.college","teacher.form","kb.use",
            "insight.funding","insight.safety","insight.academic","insight.mental","insight.employment","insight.export"));
        assertEquals(old, new FeatureServiceImpl(null).defaults("user"));
    }

    @Test
    void college_admin_has_dev_entry_and_dashboard() {
        Set<String> s = new FeatureServiceImpl(null).defaults("college_admin");
        assertTrue(s.contains("dev.entry"));
        assertTrue(s.contains("admin.dashboard"));
        assertFalse(s.contains("chat.all"));
    }
}
