package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精准资助分析（AI 数据洞察 · 工作台）。
 * 只读查询 PG 数仓：消费 t_ykt_jkb（分→元换算，食堂=终端字典 zdmc 含"餐厅/食堂"）
 * + 宿管考勤最新日做学院/年级/姓名映射（t_bzks 无 yxmc/njmc 列）。
 * 口径（南理工"暖心饭卡"模式）：达标=观察窗内食堂就餐 ≥40 顿；
 * 分位=同学院同年级单顿均价 PERCENT_RANK；高 P5 / 中 P5-15 / 观察 P15-40。
 * SQL 已在 91 数仓真实验证（2026-11-20：30 天窗达标 1078 人，三档 74/103/253）。
 */
@Tag(name = "精准资助分析")
@RestController
@RequestMapping("/api/v1/insight/funding")
public class FundingInsightController {

    private static final DateTimeFormatter D = DateTimeFormatter.ISO_LOCAL_DATE;

    @Value("${warehouse.url:jdbc:postgresql://210.28.145.81:5431/datav}")
    private String warehouseUrl;

    @Value("${warehouse.username:usr_gxai}")
    private String warehouseUsername;

    @Value("${warehouse.password:}")
    private String warehousePassword;

    private volatile JdbcTemplate warehouseJt;

    /** 惰性建池：不注册 DataSource Bean，避免覆盖 Spring Boot 主数据源（MySQL）自动配置 */
    private JdbcTemplate jt() {
        if (warehouseJt == null) {
            synchronized (this) {
                if (warehouseJt == null) {
                    try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
                    HikariDataSource ds = new HikariDataSource();
                    ds.setDriverClassName("org.postgresql.Driver");
                    ds.setJdbcUrl(warehouseUrl);
                    ds.setUsername(warehouseUsername);
                    ds.setPassword(warehousePassword);
                    ds.setMaximumPoolSize(4);
                    ds.setReadOnly(true);
                    ds.setConnectionInitSql("SET default_transaction_read_only = on; SET statement_timeout = '60s'");
                    ds.setPoolName("warehouse-readonly-pool");
                    warehouseJt = new JdbcTemplate(ds);
                }
            }
        }
        return warehouseJt;
    }

    /* ---------- 共享口径 SQL ---------- */

    /** 达标人群（观察窗内食堂就餐 ≥40 顿），?1=start ?2=end */
    private static final String PER_ELIGIBLE =
            "SELECT c.xgh FROM usr_gxai.t_ykt_jkb c LEFT JOIN usr_gxai.t_ykt_zdxx z ON z.zdbh = c.zdbh" +
            " WHERE c.zdsdzzsj >= ?::timestamp AND c.zdsdzzsj < ?::timestamp AND (z.zdmc LIKE '%餐厅%' OR z.zdmc LIKE '%食堂%')" +
            " GROUP BY c.xgh HAVING COUNT(*) >= 40";

    /** 完整分位 CTE（pct：id/name/college/grade/avg_price/meals/spend/pr），?1=start ?2=end */
    private static final String PER_PCT =
            "WITH d AS (SELECT xh, yxmc, njmc, xsxm FROM usr_gxai.t_zhsg_xskq WHERE date = (SELECT max(date) FROM usr_gxai.t_zhsg_xskq))," +
            "per AS (SELECT c.xgh, ROUND(SUM(c.xfzze)/100.0/COUNT(*),2) AS avg_price, COUNT(*) AS meals, ROUND(SUM(c.xfzze)/100.0,2) AS spend" +
            "  FROM usr_gxai.t_ykt_jkb c LEFT JOIN usr_gxai.t_ykt_zdxx z ON z.zdbh = c.zdbh" +
            "  WHERE c.zdsdzzsj >= ?::timestamp AND c.zdsdzzsj < ?::timestamp AND (z.zdmc LIKE '%餐厅%' OR z.zdmc LIKE '%食堂%')" +
            "  GROUP BY c.xgh HAVING COUNT(*) >= 40)," +
            "pct AS (SELECT per.*, d.xsxm AS name, d.yxmc AS college, d.njmc AS grade," +
            "  PERCENT_RANK() OVER (PARTITION BY d.yxmc, d.njmc ORDER BY per.avg_price) AS pr" +
            "  FROM per JOIN d ON d.xh = per.xgh)";

    /* ---------- 接口 ---------- */

    @GetMapping("/overview")
    @Operation(summary = "KPI 总览（观察窗）")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int windowDays) {
        JdbcTemplate jt = jt();
        LocalDate end = latestConsumptionDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);

        Map<String, Object> r = new HashMap<>();
        r.put("range", start.format(D) + " ~ " + end.format(D));
        r.put("dataDate", end.format(D));

        Long active = jt.queryForObject(
                "SELECT COUNT(DISTINCT c.xgh) FROM usr_gxai.t_ykt_jkb c WHERE c.zdsdzzsj >= ?::timestamp AND c.zdsdzzsj < ?::timestamp",
                Long.class, s, e);
        r.put("active", active);

        Long eligible = jt.queryForObject("SELECT COUNT(*) FROM (" + PER_ELIGIBLE + ") t", Long.class, s, e);
        r.put("eligible", eligible);

        Map<String, Object> t = jt.queryForList(
                PER_PCT + " SELECT COUNT(*) FILTER (WHERE pr<0.05) AS n_hi," +
                " COUNT(*) FILTER (WHERE pr>=0.05 AND pr<0.15) AS n_mid," +
                " COUNT(*) FILTER (WHERE pr>=0.15 AND pr<0.40) AS n_watch, COUNT(*) AS n_total FROM pct", s, e).get(0);
        r.put("hi", t.get("n_hi"));
        r.put("mid", t.get("n_mid"));
        r.put("watch", t.get("n_watch"));
        r.put("total", t.get("n_total"));
        return Result.success(r);
    }

    @GetMapping("/colleges")
    @Operation(summary = "各学院三档分布")
    public Result<List<Map<String, Object>>> colleges(@RequestParam(defaultValue = "30") int windowDays) {
        JdbcTemplate jt = jt();
        LocalDate end = latestConsumptionDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);
        List<Map<String, Object>> rows = jt.queryForList(
                PER_PCT + " SELECT college AS n," +
                "  COUNT(*) FILTER (WHERE pr < 0.05) AS hi," +
                "  COUNT(*) FILTER (WHERE pr >= 0.05 AND pr < 0.15) AS mid," +
                "  COUNT(*) FILTER (WHERE pr >= 0.15 AND pr < 0.40) AS watch" +
                " FROM pct GROUP BY college ORDER BY college", s, e);
        return Result.success(rows);
    }

    @GetMapping("/students")
    @Operation(summary = "线索名单（分页）")
    public Result<Map<String, Object>> students(
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        JdbcTemplate jt = jt();
        LocalDate end = latestConsumptionDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);

        String tierFilter = " WHERE pr < 0.40";
        if ("hi".equals(level)) tierFilter = " WHERE pr < 0.05";
        else if ("mid".equals(level)) tierFilter = " WHERE pr >= 0.05 AND pr < 0.15";
        else if ("watch".equals(level)) tierFilter = " WHERE pr >= 0.15 AND pr < 0.40";

        StringBuilder outer = new StringBuilder(tierFilter);
        if (college != null && !college.isEmpty()) {
            outer.append(" AND college = ").append(quote(college));
        }
        if (q != null && !q.trim().isEmpty()) {
            String t = q.trim().replace("'", "''");
            outer.append(" AND (xgh LIKE '%").append(t).append("%' OR name LIKE '%").append(t).append("%')");
        }

        Long total = jt.queryForObject(
                PER_PCT + " SELECT COUNT(*) FROM pct" + outer, Long.class, s, e);

        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = jt.queryForList(
                PER_PCT + " SELECT xgh AS id, name, college, grade," +
                "  CASE WHEN pr < 0.05 THEN 'hi' WHEN pr < 0.15 THEN 'mid' ELSE 'watch' END AS level," +
                "  meals, avg_price AS price, spend, ROUND(pr * 100) AS pct" +
                " FROM pct" + outer + " ORDER BY pr ASC LIMIT " + size + " OFFSET " + offset, s, e);

        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("records", rows);
        r.put("range", start.format(D) + " ~ " + end.format(D));
        return Result.success(r);
    }

    /* ---------- helpers ---------- */

    private LocalDate latestConsumptionDate(JdbcTemplate jt) {
        String d = jt.queryForObject("SELECT MAX(zdsdzzsj)::date::text FROM usr_gxai.t_ykt_jkb", String.class);
        return d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
    }

    private static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
