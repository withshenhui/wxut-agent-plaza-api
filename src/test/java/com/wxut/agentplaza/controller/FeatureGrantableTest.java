package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.service.impl.FeatureServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FeatureGrantableTest {
    @Test void dev_entry_grantable_chat_all_not() {
        Set<String> g = new FeatureServiceImpl(null).grantablePerms();
        assertTrue(g.contains("dev.entry"));
        assertFalse(g.contains("chat.all"));      // leader/admin 默认有 → 非申请开通制
    }
}
