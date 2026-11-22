package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 意见反馈（匿名提交，XixiBuddy / AI中台各端入口共用）。
 * 存储：91 agent_plaza.sys_feedback。
 */
@Slf4j
@Tag(name = "意见反馈")
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final JdbcTemplate jdbcTemplate;

    @PostMapping
    @Operation(summary = "提交反馈（匿名）")
    public Result<Void> submit(@RequestBody Map<String, String> body) {
        String content = trimTo(body.get("content"), 4000);
        if (content.isEmpty()) {
            return Result.error(400, "反馈内容不能为空");
        }
        String source = trimTo(body.get("source"), 32);
        String ftype = trimTo(body.get("ftype"), 32);
        String contact = trimTo(body.get("contact"), 128);
        String version = trimTo(body.get("version"), 64);
        String deviceInfo = trimTo(body.get("deviceInfo"), 512);
        jdbcTemplate.update(
                "INSERT INTO sys_feedback (source, ftype, content, contact, version, device_info) VALUES (?,?,?,?,?,?)",
                source, ftype, content, contact.isEmpty() ? null : contact, version.isEmpty() ? null : version, deviceInfo.isEmpty() ? null : deviceInfo);
        log.info("feedback submitted: source={} type={} len={}", source, ftype, content.length());
        return Result.success();
    }

    private static String trimTo(String v, int max) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
