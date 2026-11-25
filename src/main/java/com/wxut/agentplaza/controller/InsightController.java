package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.config.WarehouseJdbcProvider;
import com.wxut.agentplaza.entity.ChatUsage;
import com.wxut.agentplaza.mapper.ChatUsageMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据洞察 · 公开总览（真实数据源，不写死）：
 *  - 在校生：只读学籍数仓 usr_gxai.t_bzks（sfzx='01' 在校）
 *  - 近 7 天对话（按天）：chat_usage 按日累计（前端每次对话上报）
 *  - 学院活跃度 / 场景分布：agent.visit_count 按 学院 / 分类 聚合
 */
@Tag(name = "数据洞察-总览")
@Slf4j
@RestController
@RequestMapping("/api/v1/insight")
@RequiredArgsConstructor
public class InsightController {

    private final ChatUsageMapper chatUsageMapper;

    @Autowired
    private WarehouseJdbcProvider warehouseProvider;

    /** 对话上报（前端每完成一次对话调用，fire-and-forget） */
    @PostMapping("/track-chat")
    @Operation(summary = "上报一次智能体对话（按天累计）")
    public Result<Void> trackChat(@RequestBody(required = false) Map<String, Object> body) {
        try {
            if (body == null || body.get("agentId") == null) return Result.success();
            Long aid = Long.valueOf(String.valueOf(body.get("agentId")));
            ChatUsage u = new ChatUsage();
            u.setAgentId(aid);
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            chatUsageMapper.insert(u);
        } catch (Exception e) {
            log.warn("对话上报失败：{}", e.getMessage());
        }
        return Result.success();
    }

    /** 公开总览聚合（真实数据） */
    @GetMapping("/overview")
    @Operation(summary = "公开洞察总览（在校生/近7天对话/学院活跃/场景分布）")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 在校生（学籍口径）
        Map<String, Object> stu = new LinkedHashMap<>();
        try {
            Integer n = warehouseProvider.get()
                    .queryForObject("SELECT count(*) FROM usr_gxai.t_bzks WHERE sfzx='01'", Integer.class);
            stu.put("count", n);
            stu.put("asOf", LocalDate.now().toString());
        } catch (Exception e) {
            log.warn("在校生统计失败：{}", e.getMessage());
            stu.put("count", null);
        }
        out.put("students", stu);

        // 近 7 天对话（按天补零）
        List<Map<String, Object>> chat7d = new ArrayList<>();
        try {
            LocalDate from = LocalDate.now().minusDays(6);
            Map<String, Integer> map = new HashMap<>();
            for (Map<String, Object> r : chatUsageMapper.chatByDay(from)) {
                map.put(String.valueOf(r.get("d")).substring(0, 10), ((Number) r.get("n")).intValue());
            }
            for (int i = 6; i >= 0; i--) {
                LocalDate d = LocalDate.now().minusDays(i);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("d", String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth()));
                o.put("n", map.getOrDefault(d.toString(), 0));
                chat7d.add(o);
            }
        } catch (Exception e) {
            log.warn("近7天对话统计失败：{}", e.getMessage());
        }
        out.put("chat7d", chat7d);

        // 学院活跃度（智能体访问量按学院）
        List<Map<String, Object>> colleges = new ArrayList<>();
        try {
            for (Map<String, Object> r : chatUsageMapper.collegeActivity()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", r.get("name"));
                o.put("n", ((Number) r.getOrDefault("n", 0)).intValue());
                colleges.add(o);
            }
        } catch (Exception e) {
            log.warn("学院活跃度统计失败：{}", e.getMessage());
        }
        out.put("collegeActivity", colleges);

        // 场景分布（智能体访问量按分类）
        List<Map<String, Object>> scenes = new ArrayList<>();
        try {
            for (Map<String, Object> r : chatUsageMapper.sceneActivity()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("label", r.get("label"));
                o.put("n", ((Number) r.getOrDefault("n", 0)).intValue());
                scenes.add(o);
            }
        } catch (Exception e) {
            log.warn("场景分布统计失败：{}", e.getMessage());
        }
        out.put("scenes", scenes);

        return Result.success(out);
    }
}
