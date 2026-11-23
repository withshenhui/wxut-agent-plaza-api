package com.wxut.agentplaza.service;

import com.wxut.agentplaza.service.impl.FeatureServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeatureServiceTest {

    private FeatureServiceImpl svc() { // 无配置 → 用内置默认
        return new FeatureServiceImpl(null);
    }

    @Test
    void defaults_user_matches_legacy() {
        Set<String> expect = new HashSet<>(Arrays.asList(
            "plaza.view","chat.college","teacher.form","kb.use",
            "insight.funding","insight.safety","insight.academic","insight.mental","insight.employment","insight.export"));
        assertEquals(expect, svc().defaults("user"));
    }

    @Test
    void defaults_admin_matches_legacy() {
        Set<String> expect = new HashSet<>(Arrays.asList(
            "plaza.view","chat.college","chat.all",
            "insight.funding","insight.safety","insight.academic","insight.mental","insight.employment","insight.export",
            "teacher.form","kb.use","dev.entry",
            "admin.dashboard","admin.agent","admin.model","admin.user","admin.config","admin.file","admin.log","admin.xixi"));
        assertEquals(expect, svc().defaults("admin"));
    }

    @Test
    void defaults_college_admin_matches_legacy() {
        Set<String> expect = new HashSet<>(Arrays.asList(
            "plaza.view","chat.college","teacher.form","kb.use","dev.entry",
            "insight.funding","insight.safety","insight.academic","insight.mental","insight.employment","insight.export",
            "admin.dashboard"));
        assertEquals(expect, svc().defaults("college_admin"));
    }

    @Test
    void defaults_leader_matches_legacy() {
        Set<String> expect = new HashSet<>(Arrays.asList(
            "plaza.view","chat.college","chat.all",
            "insight.funding","insight.safety","insight.academic","insight.mental","insight.employment","insight.export",
            "teacher.form","kb.use","dev.entry"));
        assertEquals(expect, svc().defaults("leader"));
    }

    @Test
    void grantable_is_dev_entry_only() {
        assertEquals(new HashSet<>(Collections.singletonList("dev.entry")), svc().grantablePerms());
    }

    @Test
    void featureOfPath_matches_prefix() {
        assertEquals("insight", svc().featureOfPath("/api/v1/insight/funding/list").getKey());
        assertNull(svc().featureOfPath("/api/v1/auth/login"));
    }
}
