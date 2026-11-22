package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意见反馈（匿名提交，XixiBuddy / AI中台各端入口共用；管理端查看与处理）。
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

    @GetMapping("/list")
    @Operation(summary = "反馈列表（管理端，分页+筛选）")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String ftype,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (ftype != null && !ftype.isEmpty()) { where.append(" AND ftype=?"); args.add(ftype); }
        if (source != null && !source.isEmpty()) { where.append(" AND source=?"); args.add(source); }
        if (status != null) { where.append(" AND status=?"); args.add(status); }
        if (q != null && !q.trim().isEmpty()) { where.append(" AND (content LIKE ? OR contact LIKE ?)"); args.add("%" + q.trim() + "%"); args.add("%" + q.trim() + "%"); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_feedback" + where, Long.class, args.toArray());
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT * FROM sys_feedback" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                append(args.toArray(), s, (long)(p - 1) * s));
        return Result.success(new PageResult<>(records, total == null ? 0 : total, p, s));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "标记处理状态（管理端）")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") instanceof Number
                ? ((Number) body.get("status")).intValue() : null;
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(400, "status 只能为 0（未处理）或 1（已处理）");
        }
        int n = jdbcTemplate.update("UPDATE sys_feedback SET status=? WHERE id=?", status, id);
        if (n == 0) return Result.error(404, "反馈不存在");
        return Result.success();
    }

    private static Object[] append(Object[] base, Object... tail) {
        Object[] out = new Object[base.length + tail.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(tail, 0, out, base.length, tail.length);
        return out;
    }

    private static String trimTo(String v, int max) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
