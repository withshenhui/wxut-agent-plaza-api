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
 * AI 场景分析：学生安全分析 / 学业预警分析 / 心理健康关怀。
 * 与资助分析同款分层（红黄绿灯 = 分档），只读数仓。
 * 安全：观察窗内未归寝次数 + 晚归次数（state='6' 未归；time>=22:30 晚归）。
 * 学业：最近有成绩学期挂科门数 + 平均分（成绩数仓 2022-2023-1 后停更，自动取最新有数学期）。
 * 心理：多信号组合——未归频次 + 消费骤降（近15天食堂消费 vs 前15天降幅>=60%）。
 */
@Tag(name = "AI场景分析")
@RestController
@RequestMapping("/api/v1/insight/scene")
public class SceneInsightController {

    private static final DateTimeFormatter D = DateTimeFormatter.ISO_LOCAL_DATE;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wxut.agentplaza.service.PermissionService permissionService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wxut.agentplaza.config.WarehouseJdbcProvider warehouseProvider;

    /** 共享只读数仓池（全后端单池） */
    private JdbcTemplate jt() {
        return warehouseProvider.get();
    }

    /** 权限门（scene=safety|academic|mental） */
    private void gate(Authentication auth, String scene) {
        permissionService.assertPerm((Long) auth.getPrincipal(), "insight." + scene);
    }

    /** 范围覆写：college 范围强制本人学院 */
    private String scoped(Authentication auth, String requested) {
        return permissionService.resolveCollegeFilter((Long) auth.getPrincipal(), requested);
    }

    /** 学院分布行裁剪（college 范围只留本人学院） */
    private List<Map<String, Object>> trim(List<Map<String, Object>> rows, Authentication auth) {
        String only = scoped(auth, null);
        if (only != null) {
            final String want = only;
            rows = rows.stream().filter(r -> want.equals(r.get("n"))).collect(java.util.stream.Collectors.toList());
        }
        return rows;
    }

    /* ---------- 宿管最新日映射（姓名/学院/年级） ---------- */
    private static final String D_MAP =
            "d AS (SELECT xh, yxmc, njmc, xsxm FROM usr_gxai.t_zhsg_xskq WHERE date = (SELECT max(date) FROM usr_gxai.t_zhsg_xskq))";

    /**
     * 餐厅窗口机口径（2026-11-22 与 FundingInsightController 同步修正）：号段 1/5/6xxx，
     * 排除 1009 杜鹃园门禁、1010 旧淋浴机、1014/1015 会议签到机、1999 开水机；
     * 不 join 终端字典（同终端多行登记会翻倍流水）；消费时间为 UTC 须 +8 转北京。
     */
    private static final String CANTEEN =
            "(c.zdbh BETWEEN 1000 AND 1999 OR c.zdbh BETWEEN 5000 AND 6999) AND c.zdbh NOT IN (1009,1010,1014,1015,1999)";
    private static final String BJ = "(c.zdsdzzsj + interval '8 hour')";

    /** 消费前后半月对比 CTE（?1=前半月起 ?2=前半月止 ?3=后半月起 ?4=后半月止） */
    private static final String C12 =
            " c1 AS (SELECT xgh, SUM(xfzze)/100.0 AS s1 FROM usr_gxai.t_ykt_jkb c" +
            "  WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp AND " + CANTEEN + " GROUP BY xgh)," +
            " c2 AS (SELECT xgh, SUM(xfzze)/100.0 AS s2 FROM usr_gxai.t_ykt_jkb c" +
            "  WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp AND " + CANTEEN + " GROUP BY xgh),";

    private LocalDate latestDormDate(JdbcTemplate jt) {
        String d = jt.queryForObject("SELECT max(date) FROM usr_gxai.t_zhsg_xskq", String.class);
        return d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
    }

    /* ================= 学生安全分析 ================= */

    @GetMapping("/safety/overview")
    @Operation(summary = "安全 KPI（观察窗）")
    public Result<Map<String, Object>> safetyOverview(Authentication auth, @RequestParam(defaultValue = "30") int windowDays) {
        gate(auth, "safety");
        JdbcTemplate jt = jt();
        LocalDate end = latestDormDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);

        String base =
                "WITH " + D_MAP + ", agg AS (" +
                " SELECT s.xh," +
                "  COUNT(*) FILTER (WHERE s.state = '6') AS no_return," +
                "  COUNT(*) FILTER (WHERE s.state <> '6' AND s.time >= '2230') AS late" +
                " FROM usr_gxai.t_zhsg_xskq s" +
                " WHERE s.date >= ? AND s.date < ? GROUP BY s.xh)";
        Map<String, Object> r = new HashMap<>();
        r.put("range", start.format(D) + " ~ " + end.format(D));
        r.put("dataDate", end.format(D));
        r.put("active", jt.queryForObject("SELECT COUNT(DISTINCT s.xh) FROM usr_gxai.t_zhsg_xskq s WHERE s.date >= ? AND s.date < ?", Long.class, s, e));
        Map<String, Object> t = jt.queryForList(base +
                " SELECT COUNT(*) AS n_total," +
                " COUNT(*) FILTER (WHERE no_return >= 8) AS n_hi," +
                " COUNT(*) FILTER (WHERE no_return >= 3 AND no_return <= 7) AS n_mid," +
                " COUNT(*) FILTER (WHERE no_return >= 1 AND no_return <= 2) AS n_watch" +
                " FROM agg WHERE no_return >= 1", s, e).get(0);
        r.put("hi", t.get("n_hi")); r.put("mid", t.get("n_mid")); r.put("watch", t.get("n_watch"));
        r.put("onLeave", jt.queryForObject(
                "SELECT COUNT(*) FROM usr_gxai.t_yzsfw_xsqjb WHERE kssj <= TO_CHAR(CURRENT_DATE,'YYYY-MM-DD') AND jssj >= TO_CHAR(CURRENT_DATE,'YYYY-MM-DD')", Long.class));
        return Result.success(r);
    }

    @GetMapping("/safety/colleges")
    @Operation(summary = "安全 学院分布")
    public Result<List<Map<String, Object>>> safetyColleges(Authentication auth, @RequestParam(defaultValue = "30") int windowDays) {
        gate(auth, "safety");
        JdbcTemplate jt = jt();
        LocalDate end = latestDormDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);
        return Result.success(trim(jt.queryForList(
                "WITH " + D_MAP + ", agg AS (" +
                " SELECT s.xh," +
                "  COUNT(*) FILTER (WHERE s.state = '6') AS no_return," +
                "  COUNT(*) FILTER (WHERE s.state <> '6' AND s.time >= '2230') AS late" +
                " FROM usr_gxai.t_zhsg_xskq s WHERE s.date >= ? AND s.date < ? GROUP BY s.xh)" +
                " SELECT d.yxmc AS n," +
                "  COUNT(*) FILTER (WHERE a.no_return >= 8) AS hi," +
                "  COUNT(*) FILTER (WHERE a.no_return >= 3 AND a.no_return <= 7) AS mid," +
                "  COUNT(*) FILTER (WHERE a.no_return >= 1 AND a.no_return <= 2) AS watch" +
                " FROM agg a JOIN d ON d.xh = a.xh WHERE a.no_return >= 1" +
                " GROUP BY d.yxmc ORDER BY d.yxmc", s, e), auth));
    }

    @GetMapping("/safety/students")
    @Operation(summary = "安全 名单")
    public Result<Map<String, Object>> safetyStudents(Authentication auth, @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false) String level, @RequestParam(required = false) String college,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        gate(auth, "safety");
        college = scoped(auth, college);
        JdbcTemplate jt = jt();
        LocalDate end = latestDormDate(jt);
        LocalDate start = end.minusDays(windowDays);
        String s = start.format(D), e = end.plusDays(1).format(D);
        return Result.success(studentsCommon(jt, "safety", s, e, level, college, q, page, size));
    }

    /* ================= 学业预警分析 ================= */

    private String latestTerm(JdbcTemplate jt) {
        return jt.queryForObject(
                "SELECT xnxqid FROM usr_gxai.t_bzks_kscj WHERE isdel = '0' GROUP BY xnxqid HAVING COUNT(*) > 1000 ORDER BY xnxqid DESC LIMIT 1", String.class);
    }

    @GetMapping("/academic/overview")
    @Operation(summary = "学业 KPI（最新有数学期）")
    public Result<Map<String, Object>> academicOverview(Authentication auth) {
        gate(auth, "academic");
        JdbcTemplate jt = jt();
        String term = latestTerm(jt);
        Map<String, Object> r = new HashMap<>();
        r.put("range", "学期 " + term + "（数仓成绩数据截至该学期，之后未再同步）");
        r.put("dataDate", term);
        r.put("active", jt.queryForObject("SELECT COUNT(DISTINCT xh) FROM usr_gxai.t_bzks_kscj WHERE xnxqid = ? AND isdel = '0'", Long.class, term));
        Map<String, Object> t = jt.queryForList(
                "WITH g AS (SELECT xh AS xgh, COUNT(*) FILTER (WHERE zcj < 60) AS fail_cnt, ROUND(AVG(zcj),1) AS avg_score, COUNT(*) AS courses" +
                " FROM usr_gxai.t_bzks_kscj WHERE xnxqid = ? AND isdel = '0' GROUP BY xh)" +
                " SELECT COUNT(*) AS n_total," +
                " COUNT(*) FILTER (WHERE fail_cnt >= 3) AS n_hi," +
                " COUNT(*) FILTER (WHERE fail_cnt = 2) AS n_mid," +
                " COUNT(*) FILTER (WHERE fail_cnt = 1 AND avg_score < 65) AS n_watch FROM g", term).get(0);
        r.put("hi", t.get("n_hi")); r.put("mid", t.get("n_mid")); r.put("watch", t.get("n_watch"));
        r.put("eligible", t.get("n_total"));
        return Result.success(r);
    }

    @GetMapping("/academic/colleges")
    @Operation(summary = "学业 学院分布")
    public Result<List<Map<String, Object>>> academicColleges(Authentication auth) {
        gate(auth, "academic");
        JdbcTemplate jt = jt();
        String term = latestTerm(jt);
        return Result.success(trim(jt.queryForList(
                "WITH " + D_MAP + ", g AS (SELECT xh AS xgh, COUNT(*) FILTER (WHERE zcj < 60) AS fail_cnt, ROUND(AVG(zcj),1) AS avg_score" +
                " FROM usr_gxai.t_bzks_kscj WHERE xnxqid = ? AND isdel = '0' GROUP BY xh)" +
                " SELECT d.yxmc AS n," +
                "  COUNT(*) FILTER (WHERE g.fail_cnt >= 3) AS hi," +
                "  COUNT(*) FILTER (WHERE g.fail_cnt = 2) AS mid," +
                "  COUNT(*) FILTER (WHERE g.fail_cnt = 1 AND g.avg_score < 65) AS watch" +
                " FROM g JOIN d ON d.xh = g.xgh GROUP BY d.yxmc ORDER BY d.yxmc", term), auth));
    }

    @GetMapping("/academic/students")
    @Operation(summary = "学业 名单")
    public Result<Map<String, Object>> academicStudents(
            Authentication auth,
            @RequestParam(required = false) String level, @RequestParam(required = false) String college,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        gate(auth, "academic");
        college = scoped(auth, college);
        JdbcTemplate jt = jt();
        String term = latestTerm(jt);
        return Result.success(studentsCommon(jt, "academic", term, null, level, college, q, page, size));
    }

    /* ================= 心理健康关怀 ================= */

    @GetMapping("/mental/overview")
    @Operation(summary = "心理关怀 KPI（多信号组合，近30天）")
    public Result<Map<String, Object>> mentalOverview(Authentication auth) {
        gate(auth, "mental");
        JdbcTemplate jt = jt();
        LocalDate cEnd = latestConsumptionDate(jt);
        String half2s = cEnd.minusDays(14).format(D), e = cEnd.plusDays(1).format(D);
        String half1s = cEnd.minusDays(29).format(D), half1e = half2s;
        LocalDate dEnd = latestDormDate(jt);
        String ds = dEnd.minusDays(29).format(D), de = dEnd.plusDays(1).format(D);

        Map<String, Object> r = new HashMap<>();
        r.put("range", half1s + " ~ " + cEnd.format(D));
        r.put("dataDate", cEnd.format(D));
        String sig =
                "WITH " + D_MAP + "," +
                C12 +
                " nr AS (SELECT xh AS xgh, COUNT(*) AS no_return FROM usr_gxai.t_zhsg_xskq WHERE date >= ? AND date < ? AND state = '6' GROUP BY xh)," +
                " sig AS (SELECT COALESCE(c2.xgh, nr.xgh) AS xgh," +
                "  CASE WHEN c1.s1 > 0 THEN ROUND((c1.s1 - COALESCE(c2.s2,0)) / c1.s1 * 100) ELSE 0 END AS drop_pct," +
                "  COALESCE(nr.no_return, 0) AS no_return FROM c2" +
                "  LEFT JOIN c1 ON c1.xgh = c2.xgh LEFT JOIN nr ON nr.xgh = c2.xgh" +
                "  WHERE (c1.s1 > 0 AND COALESCE(c2.s2,0) < c1.s1 * 0.4) OR nr.no_return >= 5)";
        r.put("active", jt.queryForObject("SELECT COUNT(DISTINCT c.xgh) FROM usr_gxai.t_ykt_jkb c WHERE " + BJ + " >= ?::timestamp AND " + BJ + " < ?::timestamp", Long.class, half1s, e));
        Map<String, Object> t = jt.queryForList(sig +
                " SELECT COUNT(*) AS n_total," +
                " COUNT(*) FILTER (WHERE drop_pct >= 60 AND no_return >= 3) AS n_hi," +
                " COUNT(*) FILTER (WHERE drop_pct >= 60 AND no_return < 3) AS n_mid," +
                " COUNT(*) FILTER (WHERE drop_pct >= 40 AND drop_pct < 60) AS n_watch FROM sig",
                half1s, half1e, half2s, e, ds, de).get(0);
        r.put("hi", t.get("n_hi")); r.put("mid", t.get("n_mid")); r.put("watch", t.get("n_watch"));
        r.put("eligible", t.get("n_total"));
        return Result.success(r);
    }

    @GetMapping("/mental/colleges")
    @Operation(summary = "心理 学院分布")
    public Result<List<Map<String, Object>>> mentalColleges(Authentication auth) {
        gate(auth, "mental");
        JdbcTemplate jt = jt();
        LocalDate cEnd = latestConsumptionDate(jt);
        String half2s = cEnd.minusDays(14).format(D), e = cEnd.plusDays(1).format(D);
        String half1s = cEnd.minusDays(29).format(D), half1e = half2s;
        LocalDate dEnd = latestDormDate(jt);
        String ds = dEnd.minusDays(29).format(D), de = dEnd.plusDays(1).format(D);
        return Result.success(trim(jt.queryForList(
                "WITH " + D_MAP + "," +
                C12 +
                " nr AS (SELECT xh AS xgh, COUNT(*) AS no_return FROM usr_gxai.t_zhsg_xskq WHERE date >= ? AND date < ? AND state = '6' GROUP BY xh)," +
                " sig AS (SELECT COALESCE(c2.xgh, nr.xgh) AS xgh," +
                "  CASE WHEN c1.s1 > 0 THEN ROUND((c1.s1 - COALESCE(c2.s2,0)) / c1.s1 * 100) ELSE 0 END AS drop_pct," +
                "  COALESCE(nr.no_return, 0) AS no_return FROM c2" +
                "  LEFT JOIN c1 ON c1.xgh = c2.xgh LEFT JOIN nr ON nr.xgh = c2.xgh" +
                "  WHERE (c1.s1 > 0 AND COALESCE(c2.s2,0) < c1.s1 * 0.4) OR nr.no_return >= 5)" +
                " SELECT d.yxmc AS n," +
                "  COUNT(*) FILTER (WHERE sig.drop_pct >= 60 AND sig.no_return >= 3) AS hi," +
                "  COUNT(*) FILTER (WHERE sig.drop_pct >= 60 AND sig.no_return < 3) AS mid," +
                "  COUNT(*) FILTER (WHERE sig.drop_pct >= 40 AND sig.drop_pct < 60) AS watch" +
                " FROM sig JOIN d ON d.xh = sig.xgh GROUP BY d.yxmc ORDER BY d.yxmc",
                half1s, half1e, half2s, e, ds, de), auth));
    }

    @GetMapping("/mental/students")
    @Operation(summary = "心理 名单")
    public Result<Map<String, Object>> mentalStudents(
            Authentication auth,
            @RequestParam(required = false) String level, @RequestParam(required = false) String college,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        gate(auth, "mental");
        college = scoped(auth, college);
        JdbcTemplate jt = jt();
        LocalDate cEnd = latestConsumptionDate(jt);
        String half2s = cEnd.minusDays(14).format(D), e = cEnd.plusDays(1).format(D);
        String half1s = cEnd.minusDays(29).format(D), half1e = half2s;
        LocalDate dEnd = latestDormDate(jt);
        String ds = dEnd.minusDays(29).format(D), de = dEnd.plusDays(1).format(D);
        return Result.success(mentalStudentsImpl(jt, half1s, half1e, half2s, e, ds, de, level, college, q, page, size));
    }

    /* ---------- 详情：未归明细 + 请假交叉（safety/mental 共用） ---------- */

    @GetMapping("/detail")
    @Operation(summary = "学生未归明细 + 请假交叉核验")
    public Result<Map<String, Object>> detail(Authentication auth, @RequestParam String studentId, @RequestParam(defaultValue = "30") int windowDays) {
        // safety 或 mental 任一权限即可看明细
        Long uid = (Long) auth.getPrincipal();
        if (!permissionService.has(uid, "insight.safety") && !permissionService.has(uid, "insight.mental")) {
            throw new com.wxut.agentplaza.exception.BusinessException(403, "无权使用该功能，如需开通请联系管理员");
        }
        JdbcTemplate jt = jt();
        // 数据范围：college 范围校验该学生属于本人学院
        String only = scoped(auth, null);
        if (only != null) {
            List<String> belongs = jt.queryForList(
                    "SELECT DISTINCT yxmc FROM usr_gxai.t_zhsg_xskq WHERE xh = ? LIMIT 1", String.class, studentId);
            String studentCollege = belongs.isEmpty() ? null : belongs.get(0);
            String mapped = studentCollege == null ? null : (com.wxut.agentplaza.service.impl.CollegeSyncServiceImpl.collegeNameOf(studentCollege));
            if (mapped == null || !mapped.equals(only)) {
                throw new com.wxut.agentplaza.exception.BusinessException(403, "该学生不在您所在学院的数据范围内");
            }
        }
        LocalDate dEnd = latestDormDate(jt);
        LocalDate dStart = dEnd.minusDays(windowDays);
        String ds = dStart.format(D), de = dEnd.plusDays(1).format(D);

        // 未归/晚归明细
        List<Map<String, Object>> nights = jt.queryForList(
                "SELECT date, time, state FROM usr_gxai.t_zhsg_xskq" +
                " WHERE xh = ? AND date >= ? AND date < ? AND (state = '6' OR (state <> '6' AND time >= '2230'))" +
                " ORDER BY date DESC", studentId, ds, de);

        // 观察窗内相关请假（有效行 c；kssj/jssj 是 timestamp with tz，取 ::date 比较）
        List<Map<String, Object>> leaves = jt.queryForList(
                "SELECT kssj::date AS start_date, jssj::date AS end_date, qjlx, left(wcsy, 30) AS reason" +
                " FROM usr_gxai.t_yzsfw_xsqjb WHERE xh = ? AND dke_op_type = 'c'" +
                " AND jssj >= ? AND kssj <= ?" +
                " ORDER BY kssj DESC LIMIT 20", studentId, ds + " 00:00", de + " 23:59");

        // 交叉：每个未归日是否在请假区间内
        java.util.Set<String> leaveDays = new java.util.HashSet<>();
        for (Map<String, Object> lv : leaves) {
            LocalDate st = LocalDate.parse(String.valueOf(lv.get("start_date")).substring(0, 10));
            LocalDate en = LocalDate.parse(String.valueOf(lv.get("end_date")).substring(0, 10));
            for (LocalDate d = st; !d.isAfter(en); d = d.plusDays(1)) leaveDays.add(d.toString());
        }
        int matched = 0, unmatched = 0;
        List<Map<String, Object>> rowsOut = new java.util.ArrayList<>();
        for (Map<String, Object> n : nights) {
            String d = String.valueOf(n.get("date"));
            boolean onLeave = leaveDays.contains(d.length() > 10 ? d.substring(0, 10) : d);
            if (onLeave) matched++; else unmatched++;
            Map<String, Object> row = new HashMap<>(n);
            row.put("onLeave", onLeave);
            rowsOut.add(row);
        }

        Map<String, Object> r = new HashMap<>();
        r.put("nights", rowsOut);
        r.put("leaves", leaves);
        r.put("matched", matched);
        r.put("unmatched", unmatched);
        return Result.success(r);
    }

    /* ---------- 共用 students 实现 ---------- */

    private Map<String, Object> studentsCommon(JdbcTemplate jt, String scene, String p1, String p2,
            String level, String college, String q, int page, int size) {
        if ("academic".equals(scene)) return academicStudentsImpl(jt, p1, level, college, q, page, size);
        return safetyStudentsImpl(jt, p1, p2, level, college, q, page, size);
    }

    private Map<String, Object> safetyStudentsImpl(JdbcTemplate jt, String s, String e,
            String level, String college, String q, int page, int size) {
        String sig =
                "WITH " + D_MAP + ", agg AS (" +
                " SELECT xh AS xgh, COUNT(*) FILTER (WHERE state = '6') AS no_return," +
                "  COUNT(*) FILTER (WHERE state <> '6' AND time >= '2230') AS late" +
                " FROM usr_gxai.t_zhsg_xskq WHERE date >= ? AND date < ? GROUP BY xh)," +
                " sig AS (SELECT agg.*, d.xsxm AS name, d.yxmc AS college, d.njmc AS grade," +
                "  CASE WHEN no_return >= 8 THEN 'hi'" +
                "       WHEN no_return >= 3 THEN 'mid' ELSE 'watch' END AS level" +
                " FROM agg JOIN d ON d.xh = agg.xgh WHERE no_return >= 1)";
        return queryStudents(jt, sig, s, e, level, college, q, page, size,
                "xgh AS id, name, college, grade, level, no_return AS m1, late AS m2, (no_return + late) AS score");
    }

    private Map<String, Object> academicStudentsImpl(JdbcTemplate jt, String term,
            String level, String college, String q, int page, int size) {
        String sig =
                "WITH " + D_MAP + ", g AS (SELECT xh AS xgh, COUNT(*) FILTER (WHERE zcj < 60) AS fail_cnt," +
                " ROUND(AVG(zcj),1) AS avg_score, COUNT(*) AS courses" +
                " FROM usr_gxai.t_bzks_kscj WHERE xnxqid = ? AND isdel = '0' GROUP BY xh)," +
                " sig AS (SELECT g.*, d.xsxm AS name, d.yxmc AS college, d.njmc AS grade," +
                "  CASE WHEN fail_cnt >= 3 THEN 'hi' WHEN fail_cnt = 2 THEN 'mid' ELSE 'watch' END AS level" +
                " FROM g JOIN d ON d.xh = g.xgh WHERE fail_cnt >= 1)";
        return queryStudents(jt, sig, term, null, level, college, q, page, size,
                "xgh AS id, name, college, grade, level, fail_cnt AS m1, avg_score AS m2, courses AS score");
    }

    private Map<String, Object> mentalStudentsImpl(JdbcTemplate jt, String h1s, String h1e, String h2s, String e, String ds, String de,
            String level, String college, String q, int page, int size) {
        String sig =
                "WITH " + D_MAP + "," +
                C12 +
                " nr AS (SELECT xh AS xgh, COUNT(*) AS no_return FROM usr_gxai.t_zhsg_xskq WHERE date >= ? AND date < ? AND state = '6' GROUP BY xh)," +
                " sig AS (SELECT COALESCE(c2.xgh, nr.xgh) AS xgh, d.xsxm AS name, d.yxmc AS college, d.njmc AS grade," +
                "  CASE WHEN c1.s1 > 0 THEN ROUND((c1.s1 - COALESCE(c2.s2,0)) / c1.s1 * 100) ELSE 0 END AS drop_pct," +
                "  COALESCE(nr.no_return,0) AS no_return," +
                "  CASE WHEN (CASE WHEN c1.s1 > 0 THEN ROUND((c1.s1 - COALESCE(c2.s2,0)) / c1.s1 * 100) ELSE 0 END) >= 60 AND COALESCE(nr.no_return,0) >= 3 THEN 'hi'" +
                "       WHEN (CASE WHEN c1.s1 > 0 THEN ROUND((c1.s1 - COALESCE(c2.s2,0)) / c1.s1 * 100) ELSE 0 END) >= 60 THEN 'mid'" +
                "       ELSE 'watch' END AS level" +
                " FROM c2 LEFT JOIN c1 ON c1.xgh = c2.xgh LEFT JOIN nr ON nr.xgh = c2.xgh JOIN d ON d.xh = c2.xgh" +
                "  WHERE (c1.s1 > 0 AND COALESCE(c2.s2,0) < c1.s1 * 0.4) OR nr.no_return >= 5)";
        // 6 参数特殊处理：手动拼
        String tierFilter = tierFilter(level);
        StringBuilder outer = new StringBuilder(tierFilter);
        boolean hasWhere = outer.length() > 0;
        if (college != null && !college.isEmpty()) {
            outer.append(hasWhere ? " AND" : " WHERE").append(" college = ").append(q(college));
            hasWhere = true;
        }
        if (q != null && !q.trim().isEmpty()) { String t = q.trim().replace("'", "''"); outer.append(hasWhere ? " AND" : " WHERE").append(" (xgh LIKE '%").append(t).append("%' OR name LIKE '%").append(t).append("%')"); }
        Long total = jt.queryForObject("SELECT COUNT(*) FROM (" + sig + " SELECT * FROM sig" + outer + ") t",
                Long.class, h1s, h1e, h2s, e, ds, de);
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = jt.queryForList(
                sig + " SELECT xgh AS id, name, college, grade, level, drop_pct AS m1, no_return AS m2, (drop_pct + no_return * 10) AS score FROM sig" +
                outer + " ORDER BY CASE level WHEN 'hi' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END, score DESC LIMIT " + size + " OFFSET " + offset, h1s, h1e, h2s, e, ds, de);
        Map<String, Object> r = new HashMap<>();
        r.put("total", total); r.put("records", rows);
        return r;
    }

    private Map<String, Object> queryStudents(JdbcTemplate jt, String sig, Object p1, Object p2,
            String level, String college, String q, int page, int size, String selectCols) {
        String tierFilter = tierFilter(level);
        StringBuilder outer = new StringBuilder(tierFilter);
        boolean hasWhere = outer.length() > 0;
        if (college != null && !college.isEmpty()) {
            outer.append(hasWhere ? " AND" : " WHERE").append(" college = ").append(q(college));
            hasWhere = true;
        }
        if (q != null && !q.trim().isEmpty()) {
            String t = q.trim().replace("'", "''");
            outer.append(hasWhere ? " AND" : " WHERE").append(" (xgh LIKE '%").append(t).append("%' OR name LIKE '%").append(t).append("%')");
        }
        Object[] params = p2 != null ? new Object[]{p1, p2} : new Object[]{p1};
        Long total = jt.queryForObject("SELECT COUNT(*) FROM (" + sig + " SELECT * FROM sig" + outer + ") t", Long.class, params);
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = jt.queryForList(
                sig + " SELECT " + selectCols + " FROM sig" + outer +
                " ORDER BY CASE level WHEN 'hi' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END, score DESC LIMIT " + size + " OFFSET " + offset, params);
        Map<String, Object> r = new HashMap<>();
        r.put("total", total); r.put("records", rows);
        return r;
    }

    private static String tierFilter(String level) {
        if ("hi".equals(level)) return " WHERE level = 'hi'";
        if ("mid".equals(level)) return " WHERE level = 'mid'";
        if ("watch".equals(level)) return " WHERE level = 'watch'";
        return "";
    }

    private static String q(String v) { return "'" + v.replace("'", "''") + "'"; }

    private LocalDate latestConsumptionDate(JdbcTemplate jt) {
        String d = jt.queryForObject("SELECT MAX(zdsdzzsj + interval '8 hour')::date::text FROM usr_gxai.t_ykt_jkb", String.class);
        return d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
    }
}
