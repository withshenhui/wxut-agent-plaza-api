package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精准资助分析（AI 数据洞察 · 工作台）。
 * 只读查询 PG 数仓：消费 t_ykt_jkb（分→元换算，餐厅=1/5/6xxx 号段窗口机，见 CANTEEN 注释）
 * + 宿管考勤最新日做学院/年级/姓名映射（t_bzks 无 yxmc/njmc 列）。
 * 口径（南理工"暖心饭卡"模式）：达标=观察窗内食堂就餐 ≥40 顿；
 * 分位=同学院同年级单顿均价 PERCENT_RANK；高 P5 / 中 P5-15 / 观察 P15-40。
 * 2026-11-22 口径修正（三流勘误后重校准，30 天窗达标 827 人，三档 66/72/190）：
 * ①餐厅识别改号段（原按字典名匹配漏 5 台数字名窗口机≈20% 流水）；
 * ②不再 join 终端字典（字典同终端多行登记，join 把 1005 等终端流水翻倍，170 人虚增达标）；
 * ③消费时间为 UTC，+8 转北京后再取窗口与"数据截至"。
 * 权限（2026-11-21 RBAC）：需 insight.funding；数据范围 college 范围强制本人学院。
 */
@Tag(name = "精准资助分析")
@RestController
@RequestMapping("/api/v1/insight/funding")
public class FundingInsightController {

    private static final DateTimeFormatter D = DateTimeFormatter.ISO_LOCAL_DATE;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wxut.agentplaza.service.PermissionService permissionService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wxut.agentplaza.config.WarehouseJdbcProvider warehouseProvider;

    /** 共享只读数仓池（2026-11-21 起全后端单池，避免挤爆 usr_gxai 角色配额） */
    private JdbcTemplate jt() {
        return warehouseProvider.get();
    }

    /** 权限门 + 范围解析（每端点入口调用） */
    private void gate(Authentication auth) {
        permissionService.assertPerm((Long) auth.getPrincipal(), "insight.funding");
    }

    /** 范围覆写：college 范围强制本人学院 */
    private String scopedCollege(Authentication auth, String requested) {
        return permissionService.resolveCollegeFilter((Long) auth.getPrincipal(), requested);
    }

    /* ---------- 共享口径 SQL ---------- */

    /**
     * 餐厅窗口机口径：一/五/六餐厅 = 终端号段 1xxx / 5xxx / 6xxx（t_ykt_zdxx 实名佐证）。
     * 排除段内非餐饮终端：1009 杜鹃园门禁、1010 旧淋浴机(2221)、1014/1015 会议签到机、1999 开水机。
     * 不 join 终端字典取名字——字典同一终端有多行登记（如 1005 有 ks26/ks27 两条），
     * join 会把该终端消费流水整倍翻倍；数字名窗口机（1004/1005/1006/1011/1012）靠号段纳入。
     */
    private static final String CANTEEN =
            "(c.zdbh BETWEEN 1000 AND 1999 OR c.zdbh BETWEEN 5000 AND 6999) AND c.zdbh NOT IN (1009,1010,1014,1015,1999)";

    /** 消费时间 zdsdzzsj 为 UTC，比较前 +8 转北京时间（否则窗口边界与"数据截至"日期偏 8h） */
    private static final String BJ = "(c.zdsdzzsj + interval '8 hour')";

    /** 达标人群（观察窗内食堂就餐 ≥40 顿），?1=start ?2=end */
    private static final String PER_ELIGIBLE =
            "SELECT c.xgh FROM usr_gxai.t_ykt_jkb c" +
            " WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp AND " + CANTEEN +
            " GROUP BY c.xgh HAVING COUNT(*) >= 40";

    /** 完整分位 CTE（pct：id/name/college/grade/avg_price/meals/spend/pr），?1=start ?2=end */
    private static final String PER_PCT =
            "WITH d AS (SELECT xh, yxmc, njmc, xsxm FROM usr_gxai.t_zhsg_xskq WHERE date = (SELECT max(date) FROM usr_gxai.t_zhsg_xskq))," +
            "per AS (SELECT c.xgh, ROUND(SUM(c.xfzze)/100.0/COUNT(*),2) AS avg_price, COUNT(*) AS meals, ROUND(SUM(c.xfzze)/100.0,2) AS spend" +
            "  FROM usr_gxai.t_ykt_jkb c" +
            "  WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp AND " + CANTEEN +
            "  GROUP BY c.xgh HAVING COUNT(*) >= 40)," +
            "pct AS (SELECT per.*, d.xsxm AS name, d.yxmc AS college, d.njmc AS grade," +
            "  PERCENT_RANK() OVER (PARTITION BY d.yxmc, d.njmc ORDER BY per.avg_price) AS pr" +
            "  FROM per JOIN d ON d.xh = per.xgh)";

    /* ---------- 接口 ---------- */

    @GetMapping("/overview")
    @Operation(summary = "KPI 总览（观察窗）")
    public Result<Map<String, Object>> overview(Authentication auth,
            @RequestParam(defaultValue = "30") int windowDays) {
        gate(auth);
        JdbcTemplate jt = jt();
        LocalDate end = latestConsumptionDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);

        Map<String, Object> r = new HashMap<>();
        r.put("range", start.format(D) + " ~ " + end.format(D));
        r.put("dataDate", end.format(D));

        Long active = jt.queryForObject(
                "SELECT COUNT(DISTINCT c.xgh) FROM usr_gxai.t_ykt_jkb c WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp",
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
    public Result<List<Map<String, Object>>> colleges(Authentication auth,
            @RequestParam(defaultValue = "30") int windowDays) {
        gate(auth);
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
        // college 范围：只返回本人学院一行
        String only = scopedCollege(auth, null);
        if (only != null) {
            final String want = only;
            rows = rows.stream().filter(r -> want.equals(r.get("n"))).collect(java.util.stream.Collectors.toList());
        }
        return Result.success(rows);
    }

    @GetMapping("/histogram")
    @Operation(summary = "单顿均价分位分布（达标人群 20 桶）")
    public Result<List<Map<String, Object>>> histogram(Authentication auth,
            @RequestParam(defaultValue = "30") int windowDays) {
        gate(auth);
        JdbcTemplate jt = jt();
        LocalDate end = latestConsumptionDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);
        List<Map<String, Object>> rows = jt.queryForList(
                PER_PCT + " SELECT" +
                "  COUNT(*) FILTER (WHERE pr < 0.05) AS p0_5," +
                "  COUNT(*) FILTER (WHERE pr >= 0.05 AND pr < 0.10) AS p5_10," +
                "  COUNT(*) FILTER (WHERE pr >= 0.10 AND pr < 0.15) AS p10_15," +
                "  COUNT(*) FILTER (WHERE pr >= 0.15 AND pr < 0.20) AS p15_20," +
                "  COUNT(*) FILTER (WHERE pr >= 0.20 AND pr < 0.25) AS p20_25," +
                "  COUNT(*) FILTER (WHERE pr >= 0.25 AND pr < 0.30) AS p25_30," +
                "  COUNT(*) FILTER (WHERE pr >= 0.30 AND pr < 0.35) AS p30_35," +
                "  COUNT(*) FILTER (WHERE pr >= 0.35 AND pr < 0.40) AS p35_40," +
                "  COUNT(*) FILTER (WHERE pr >= 0.40 AND pr < 0.45) AS p40_45," +
                "  COUNT(*) FILTER (WHERE pr >= 0.45 AND pr < 0.50) AS p45_50," +
                "  COUNT(*) FILTER (WHERE pr >= 0.50 AND pr < 0.55) AS p50_55," +
                "  COUNT(*) FILTER (WHERE pr >= 0.55 AND pr < 0.60) AS p55_60," +
                "  COUNT(*) FILTER (WHERE pr >= 0.60 AND pr < 0.65) AS p60_65," +
                "  COUNT(*) FILTER (WHERE pr >= 0.65 AND pr < 0.70) AS p65_70," +
                "  COUNT(*) FILTER (WHERE pr >= 0.70 AND pr < 0.75) AS p70_75," +
                "  COUNT(*) FILTER (WHERE pr >= 0.75 AND pr < 0.80) AS p75_80," +
                "  COUNT(*) FILTER (WHERE pr >= 0.80 AND pr < 0.85) AS p80_85," +
                "  COUNT(*) FILTER (WHERE pr >= 0.85 AND pr < 0.90) AS p85_90," +
                "  COUNT(*) FILTER (WHERE pr >= 0.90 AND pr < 0.95) AS p90_95," +
                "  COUNT(*) FILTER (WHERE pr >= 0.95) AS p95_100" +
                " FROM pct", s, e);
        // 转成 20 桶数组 [{bucket: 'P0-5', n: x, light: 'hi'|'mid'|'watch'|null}]
        String[] keys = {"p0_5","p5_10","p10_15","p15_20","p20_25","p25_30","p30_35","p35_40","p40_45","p45_50",
                "p50_55","p55_60","p60_65","p65_70","p70_75","p75_80","p80_85","p85_90","p90_95","p95_100"};
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            for (int i = 0; i < keys.length; i++) {
                Map<String, Object> b = new HashMap<>();
                b.put("bucket", "P" + (i * 5) + "-" + (i * 5 + 5));
                b.put("n", ((Number) r.get(keys[i])).intValue());
                b.put("light", i < 1 ? "hi" : i < 3 ? "mid" : i < 8 ? "watch" : null);
                out.add(b);
            }
        }
        return Result.success(out);
    }

    @GetMapping("/students")
    @Operation(summary = "线索名单（分页）")
    public Result<Map<String, Object>> students(
            Authentication auth,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        gate(auth);
        // 数据范围：college 范围忽略请求参数强制本人学院（all 范围放行）
        college = scopedCollege(auth, college);
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
        // 同档内排序：分位越小（消费相对越低）越靠前；分位相同再按顿数多者优先（数据更扎实）。仅看顿数会把P更小的学生压后。
        List<Map<String, Object>> rows = jt.queryForList(
                PER_PCT + " SELECT * FROM (SELECT xgh AS id, name, college, grade," +
                "  CASE WHEN pr < 0.05 THEN 'hi' WHEN pr < 0.15 THEN 'mid' ELSE 'watch' END AS level," +
                "  meals, avg_price AS price, spend, ROUND(pr * 100) AS pct" +
                " FROM pct" + outer + ") ranked" +
                " ORDER BY CASE level WHEN 'hi' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END, pct ASC, meals DESC LIMIT " + size + " OFFSET " + offset, s, e);

        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("records", rows);
        r.put("range", start.format(D) + " ~ " + end.format(D));
        return Result.success(r);
    }

    /* ---------- helpers ---------- */

    private LocalDate latestConsumptionDate(JdbcTemplate jt) {
        String d = jt.queryForObject("SELECT MAX(zdsdzzsj + interval '8 hour')::date::text FROM usr_gxai.t_ykt_jkb", String.class);
        return d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
    }

    private static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
