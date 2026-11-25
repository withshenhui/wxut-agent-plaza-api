package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话管理（管理员监督/审计，只读）。
 *
 * <p>管理员可查看 91 agent_plaza.chat_session/chat_message 中所有用户 × 所有智能体的对话（PC 与企微均已落这两张表）。
 * 仅 ADMIN 可用（@PreAuthorize，与 {@link AdminPreviewController} 同款权限语义），不提供删除/修改。
 *
 * <p>数据口径：
 * <ul>
 *   <li>列表按 updated_at DESC；msgCount 用相关子查询，避免 N+1；用户信息 join sys_user，智能体名 join agent。</li>
 *   <li>q 模糊匹配标题或工号/姓名；userId/agentId 精确；from/to 按 updated_at 的日期过滤（含当日）。</li>
 * </ul>
 */
@Tag(name = "管理后台")
@RestController
@RequestMapping("/api/v1/admin/conversations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConversationsController {

    private final JdbcTemplate jdbcTemplate;

    private static final String FROM_JOIN =
            " FROM chat_session s"
                    + " LEFT JOIN sys_user u ON u.id = s.user_id"
                    + " LEFT JOIN agent a ON a.id = s.agent_id";

    @GetMapping
    @Operation(summary = "对话列表（管理员，分页+筛选，只读）")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.trim().isEmpty()) {
            where.append(" AND (s.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (userId != null) { where.append(" AND s.user_id = ?"); args.add(userId); }
        if (agentId != null) { where.append(" AND s.agent_id = ?"); args.add(agentId); }
        if (from != null && !from.trim().isEmpty()) {
            where.append(" AND s.updated_at >= ?");
            args.add(from.trim() + " 00:00:00");
        }
        if (to != null && !to.trim().isEmpty()) {
            where.append(" AND s.updated_at <= ?");
            args.add(to.trim() + " 23:59:59");
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + FROM_JOIN + where, Long.class, args.toArray());

        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) (p - 1) * s);

        List<Map<String, Object>> records = jdbcTemplate.query(
                "SELECT s.id AS sessionId, s.user_id AS userId, u.username AS username, u.nickname AS nickname,"
                        + " s.agent_id AS agentId, a.name AS agentName, s.title AS title,"
                        + " (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS msgCount,"
                        + " s.created_at AS createdAt, s.updated_at AS updatedAt"
                        + FROM_JOIN + where
                        + " ORDER BY s.updated_at DESC"
                        + " LIMIT ? OFFSET ?",
                pageArgs.toArray(),
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", rs.getString("sessionId"));
                    m.put("userId", rs.getObject("userId"));
                    m.put("username", rs.getString("username"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("agentId", rs.getObject("agentId"));
                    m.put("agentName", rs.getString("agentName"));
                    m.put("title", rs.getString("title"));
                    m.put("msgCount", rs.getLong("msgCount"));
                    m.put("createdAt", rs.getString("createdAt"));
                    m.put("updatedAt", rs.getString("updatedAt"));
                    return m;
                });

        return Result.success(new PageResult<>(records, total == null ? 0 : total, p, s));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "对话详情（管理员，含消息，按时间升序，只读）")
    public Result<Map<String, Object>> detail(@PathVariable String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT s.id AS sessionId, s.user_id AS userId, u.username AS username, u.nickname AS nickname,"
                        + " s.agent_id AS agentId, a.name AS agentName, s.title AS title"
                        + FROM_JOIN
                        + " WHERE s.id = ?",
                new Object[]{sessionId},
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", rs.getString("sessionId"));
                    m.put("userId", rs.getObject("userId"));
                    m.put("username", rs.getString("username"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("agentId", rs.getObject("agentId"));
                    m.put("agentName", rs.getString("agentName"));
                    m.put("title", rs.getString("title"));
                    return m;
                });
        if (rows.isEmpty()) {
            return Result.error(404, "会话不存在");
        }

        List<Map<String, Object>> messages = jdbcTemplate.query(
                "SELECT sender, content, created_at AS createdAt FROM chat_message"
                        + " WHERE session_id = ? ORDER BY created_at ASC, id ASC",
                new Object[]{sessionId},
                (rs, rowNum) -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("sender", rs.getString("sender"));
                    mm.put("content", rs.getString("content"));
                    mm.put("createdAt", rs.getString("createdAt"));
                    return mm;
                });

        Map<String, Object> r = rows.get(0);
        r.put("messages", messages);
        return Result.success(r);
    }
}
