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

    @org.springframework.beans.factory.annotation.Autowired
    private com.wxut.agentplaza.service.form.FormLlmService llm;

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
    /** 时间窗比较的 sargable 写法：+8 小时挪到常量侧（与 BJ 等价），使 idx(zdsdzzsj) 可用；参数顺序不变（start,end）。 */
    private static final String BJ_WIN = "(c.zdsdzzsj >= (?::timestamp - interval '8 hour') AND c.zdsdzzsj < (?::timestamp - interval '8 hour'))";

    /** 消费前后半月对比 CTE（?1=前半月起 ?2=前半月止 ?3=后半月起 ?4=后半月止） */
    private static final String C12 =
            " c1 AS (SELECT xgh, SUM(xfzze)/100.0 AS s1 FROM usr_gxai.t_ykt_jkb c" +
            "  WHERE " + BJ_WIN + " AND " + CANTEEN + " GROUP BY xgh)," +
            " c2 AS (SELECT xgh, SUM(xfzze)/100.0 AS s2 FROM usr_gxai.t_ykt_jkb c" +
            "  WHERE " + BJ_WIN + " AND " + CANTEEN + " GROUP BY xgh),";

    private static volatile LocalDate dormDateCache;
    private static volatile long dormDateCacheAt;

    /** 数据最新日一天才变一次：10分钟静态缓存，省每次请求的全表 max(date) */
    private LocalDate latestDormDate(JdbcTemplate jt) {
        long now = System.currentTimeMillis();
        LocalDate c = dormDateCache;
        if (c != null && now - dormDateCacheAt < 600000L) return c;
        String d = jt.queryForObject("SELECT max(date) FROM usr_gxai.t_zhsg_xskq", String.class);
        LocalDate r = d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
        dormDateCache = r; dormDateCacheAt = now;
        return r;
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
        r.put("active", jt.queryForObject("SELECT COUNT(DISTINCT c.xgh) FROM usr_gxai.t_ykt_jkb c WHERE " + BJ_WIN, Long.class, half1s, e));
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

    /* ---------- 详情：学生档案 + 分维度核验数据包 ---------- */

    /** 政治面貌/民族代码 → 中文（t_bzks 存代码） */
    private static String zzmmOf(String dm) {
        if (dm == null) return null;
        switch (dm) { case "01": return "中共党员"; case "02": return "中共预备党员"; case "03": return "共青团员";
            case "13": return "群众"; default: return dm; }
    }

    private static String nz(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    /**
     * 学生详情（四工作台共用）。
     * profile：学籍档案（籍贯/生源/通讯地址/民族/政治面貌/出生，t_bzks 覆盖约 43%，缺省不返回）。
     * 按维度返回差异化核验数据：safety/mental=未归明细+请假交叉；academic=单科成绩明细；
     * funding/mental=食堂消费趋势（判断消费变化是行为骤变还是长期习惯）。
     */
    @GetMapping("/detail")
    @Operation(summary = "学生详情：档案 + 维度核验数据")
    public Result<Map<String, Object>> detail(Authentication auth, @RequestParam String studentId,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false, defaultValue = "scene") String view) {
        Long uid = (Long) auth.getPrincipal();
        if (!permissionService.has(uid, "insight.safety") && !permissionService.has(uid, "insight.mental")
                && !permissionService.has(uid, "insight.academic") && !permissionService.has(uid, "insight.funding")) {
            throw new com.wxut.agentplaza.exception.BusinessException(403, "无权使用该功能，如需开通请联系管理员");
        }
        JdbcTemplate jt = jt();
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
        return Result.success(buildDetail(studentId, windowDays, view));
    }
    /** 详情数据组装（detail 与 detail/ai 共用） */
    private Map<String, Object> buildDetail(String studentId, int windowDays, String view) {
        JdbcTemplate jt = jt();
        Map<String, Object> r = new HashMap<>();

        /* 学籍档案（t_bzks，字段可空率高，有值才返回） */
        List<Map<String, Object>> prof = jt.queryForList(
                "SELECT jgdm, syszd, txdz, mzdm, zzmmdm, csrq::text, xbdm FROM usr_gxai.t_bzks" +
                " WHERE xh = ? AND dke_op_type <> 'c' AND (jgdm IS NOT NULL OR syszd IS NOT NULL OR txdz IS NOT NULL) LIMIT 1", studentId);
        if (!prof.isEmpty()) {
            Map<String, Object> p = new HashMap<>();
            Map<String, Object> row = prof.get(0);
            String jg = nz(row.get("jgdm")); if (!jg.isEmpty()) p.put("jg", jg);
            String sy = nz(row.get("syszd")); if (!sy.isEmpty()) p.put("sy", sy);
            String tx = nz(row.get("txdz")); if (!tx.isEmpty()) p.put("tx", tx);
            String mz = nz(row.get("mzdm")); if (!mz.isEmpty()) p.put("mz", "01".equals(mz) ? "汉族" : mz);
            String xb = nz(row.get("xbdm")); if ("1".equals(xb)) p.put("xb", "男"); else if ("2".equals(xb)) p.put("xb", "女");
            String zz = zzmmOf(nz(row.get("zzmmdm"))); if (zz != null) p.put("zz", zz);
            String cs = nz(row.get("csrq")); if (!cs.isEmpty() && !"1900-01-01".equals(cs)) p.put("cs", cs);
            if (!p.isEmpty()) r.put("profile", p);
        }
        /* 宿舍信息（宿管最新日） */
        LocalDate dMax = latestDormDate(jt);
        List<Map<String, Object>> dorm = jt.queryForList(
                "SELECT jzwmc, lcmc, fjmc FROM usr_gxai.t_zhsg_xskq WHERE xh = ? AND date = ? LIMIT 1", studentId, dMax.format(D));
        if (!dorm.isEmpty() && dorm.get(0).get("fjmc") != null) {
            Map<String, Object> d = dorm.get(0);
            r.put("dorm", nz(d.get("jzwmc")) + " " + nz(d.get("lcmc")) + " " + nz(d.get("fjmc")));
        }

        LocalDate dEnd = latestDormDate(jt);
        LocalDate dStart = dEnd.minusDays(windowDays);
        String ds = dStart.format(D), de = dEnd.plusDays(1).format(D);

        /* safety/mental：未归明细 + 请假交叉（原有逻辑） */
        boolean wantNights = "safety".equals(view) || "mental".equals(view) || "scene".equals(view);
        if (wantNights) {
            List<Map<String, Object>> nights = jt.queryForList(
                    "SELECT date, time, state FROM usr_gxai.t_zhsg_xskq" +
                    " WHERE xh = ? AND date >= ? AND date < ? AND (state = '6' OR (state <> '6' AND time >= '2230'))" +
                    " ORDER BY date DESC", studentId, ds, de);
            List<Map<String, Object>> leaves = jt.queryForList(
                    "SELECT kssj::date AS start_date, jssj::date AS end_date, qjlx, left(wcsy, 30) AS reason" +
                    " FROM usr_gxai.t_yzsfw_xsqjb WHERE xh = ? AND dke_op_type = 'c'" +
                    " AND jssj >= ? AND kssj <= ?" +
                    " ORDER BY kssj DESC LIMIT 20", studentId, ds + " 00:00", de + " 23:59");
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
            r.put("nights", rowsOut);
            r.put("leaves", leaves);
            r.put("matched", matched);
            r.put("unmatched", unmatched);
        }

        /* academic：单科成绩明细（最新有数学期） */
        if ("academic".equals(view) || "scene".equals(view)) {
            List<String> terms = jt.queryForList(
                    "SELECT xq FROM usr_gxai.t_jw_xscjjd WHERE xh = ? AND dke_op_type <> 'c' AND cj IS NOT NULL ORDER BY xq DESC LIMIT 1", String.class, studentId);
            if (!terms.isEmpty()) {
                String term = terms.get(0);
                List<Map<String, Object>> scores = jt.queryForList(
                        "SELECT kcmc, kclb, cj, xf FROM usr_gxai.t_jw_xscjjd" +
                        " WHERE xh = ? AND xq = ? AND dke_op_type <> 'c' AND cj IS NOT NULL ORDER BY kcmc", studentId, term);
                r.put("term", term);
                r.put("scores", scores);
            }
        }

        /* funding/mental：食堂消费趋势（按周聚合，观察窗内） */
        if ("funding".equals(view) || "mental".equals(view) || "scene".equals(view)) {
            LocalDate cEnd = latestConsumptionDate(jt);
            String cs2 = cEnd.minusDays(windowDays).format(D), ce2 = cEnd.plusDays(1).format(D);
            List<Map<String, Object>> trend = jt.queryForList(
                    "SELECT to_char(" + BJ + " - interval '3 day', 'IW') AS wk," +
                    " COUNT(*) AS meals, ROUND(SUM(xfzze)/100.0) AS amt" +
                    " FROM usr_gxai.t_ykt_jkb c WHERE " + BJ_WIN + " AND " + CANTEEN +
                    " AND c.xgh = ? GROUP BY 1 ORDER BY 1", cs2, ce2, studentId);
            r.put("consumption", trend);
        }

        /* funding/scene：排除项核验真实数据（请假天数 + 消费覆盖天数 + 单笔最大） */
        if ("funding".equals(view) || "scene".equals(view)) {
            List<Map<String, Object>> leaves = jt.queryForList(
                    "SELECT kssj::date AS start_date, jssj::date AS end_date, qjlx, left(wcsy, 30) AS reason" +
                    " FROM usr_gxai.t_yzsfw_xsqjb WHERE xh = ? AND dke_op_type = 'c'" +
                    " AND jssj >= ? AND kssj <= ?" +
                    " ORDER BY kssj DESC LIMIT 20", studentId, ds + " 00:00", de + " 23:59");
            java.util.Set<String> leaveDays = new java.util.HashSet<>();
            for (Map<String, Object> lv : leaves) {
                LocalDate st = LocalDate.parse(String.valueOf(lv.get("start_date")).substring(0, 10));
                LocalDate en = LocalDate.parse(String.valueOf(lv.get("end_date")).substring(0, 10));
                for (LocalDate d = st; !d.isAfter(en); d = d.plusDays(1)) leaveDays.add(d.toString());
            }
            r.put("leaves", leaves);
            r.put("leaveDays", leaveDays.size());
            Map<String, Object> cov = jt.queryForMap(
                    "SELECT COUNT(DISTINCT (" + BJ + ")::date) AS cover, COALESCE(MAX(xfzze), 0) AS max_amt" +
                    " FROM usr_gxai.t_ykt_jkb c WHERE " + BJ_WIN + " AND " + CANTEEN +
                    " AND c.xgh = ?", ds, de, studentId);
            r.put("coverDays", ((Number) cov.get("cover")).intValue());
            r.put("maxAmt", ((Number) cov.get("max_amt")).doubleValue() / 100.0);
        }
        return r;
    }

    private static final String AI_SYS =
            "你是高校学生资助工作的数据分析助手。基于给定的学生数据写一段深度分析，供辅导员人工核实时参考。"
            + "要求：1) 交叉综合所有维度（消费结构与同群分位对比、周消费趋势形态、请假与消费变化的关系、档案背景）；"
            + "2) 明确指出2-3条最值得辅导员关注的证据；3) 给出谨慎的判断方向与下一步具体建议（如谈心谈话、家庭情况核实、按资助流程处理）；"
            + "4) 220字以内；5) 只使用给定数据，不得编造或外推未提供的数据；6) 措辞温和克制，不给学生贴标签，结尾提醒结果仅供人工核实参考、勿向学生提及被系统识别。";

    /** AI深度分析：SSE流式——服务端组装完整上下文 → 当前生效大模型边生成边推 */
    @GetMapping(value = "/detail/ai", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "学生详情AI深度分析（LLM，SSE流式）")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter detailAi(Authentication auth, @RequestParam String studentId,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false, defaultValue = "funding") String view,
            @RequestParam(required = false) Integer pct,
            @RequestParam(required = false) String level,
            javax.servlet.http.HttpServletResponse response) {
        // 关闭nginx对该响应的缓冲，保证SSE增量不被攒到结束一次性下发
        response.setHeader("X-Accel-Buffering", "no");
        Long uid = (Long) auth.getPrincipal();
        if (!permissionService.has(uid, "insight.safety") && !permissionService.has(uid, "insight.mental")
                && !permissionService.has(uid, "insight.academic") && !permissionService.has(uid, "insight.funding")) {
            throw new com.wxut.agentplaza.exception.BusinessException(403, "无权使用该功能，如需开通请联系管理员");
        }
        JdbcTemplate jt = jt();
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
        String safeLevel = ("hi".equals(level) || "mid".equals(level) || "watch".equals(level)) ? level : null;
        int safePct = (pct != null && pct >= 0 && pct <= 100) ? pct : -1;
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120000L);
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 首事件立即冲刷响应链路（防代理攒流）
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().comment("ready"));
                    Map<String, Object> d = buildDetail(studentId, windowDays, view);
                    String ctx = aiContext(d, windowDays, safePct, safeLevel);
                    final org.springframework.web.servlet.mvc.method.annotation.SseEmitter em = emitter;
                    // 推理模型：思考增量发 think 事件（前端灰字显示），正文增量发默认 data 事件
                    llm.chatStream(AI_SYS, ctx,
                        new java.util.function.Consumer<String>() {
                            public void accept(String think) {
                                try { em.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("think").data(think)); }
                                catch (Exception ignore) {}
                            }
                        },
                        new java.util.function.Consumer<String>() {
                            public void accept(String delta) {
                                try { em.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().data(delta)); }
                                catch (Exception ignore) {}
                            }
                        });
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (Exception e) {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error")
                                .data("ai_unavailable"));
                        emitter.complete();
                    } catch (Exception ignore) {}
                }
            }
        }, "insight-detail-ai").start();
        return emitter;
    }

    private String aiContext(Map<String, Object> d, int win, int safePct, String safeLevel) {
        StringBuilder sb = new StringBuilder();
        sb.append("学生近").append(win).append("天数据如下（分位=同学院同年级单顿均价相对位置，数字越小越省）：\n");
        List<Map<String, Object>> trend = (List<Map<String, Object>>) d.get("consumption");
        int meals = 0; double spend = 0; java.util.List<String> weeks = new java.util.ArrayList<>();
        if (trend != null) {
            for (Map<String, Object> t : trend) {
                int m = ((Number) t.get("meals")).intValue();
                double a = ((Number) t.get("amt")).doubleValue();
                meals += m; spend += a; weeks.add(String.valueOf(Math.round(a)));
            }
        }
        sb.append("食堂就餐 ").append(meals).append(" 顿，覆盖 ").append(d.get("coverDays")).append("/").append(win)
          .append(" 天，总支出 ").append(String.format("%.0f", spend)).append(" 元，单顿均价 ")
          .append(meals > 0 ? String.format("%.2f", spend / meals) : "—").append(" 元");
        if (safePct >= 0) sb.append("，消费分位 P").append(safePct);
        if (safeLevel != null) sb.append("，档位 ").append("hi".equals(safeLevel) ? "红(高关注)" : "mid".equals(safeLevel) ? "黄(中关注)" : "绿(观察)");
        sb.append("。\n");
        if (!weeks.isEmpty()) sb.append("周消费金额序列（元）：").append(String.join("、", weeks)).append("。\n");
        if (d.get("maxAmt") != null) sb.append("单笔最大消费 ").append(String.format("%.1f", ((Number) d.get("maxAmt")).doubleValue())).append(" 元。\n");
        if (d.get("leaveDays") != null) {
            sb.append("请假 ").append(d.get("leaveDays")).append(" 天");
            List<Map<String, Object>> lv = (List<Map<String, Object>>) d.get("leaves");
            if (lv != null && !lv.isEmpty()) {
                sb.append("：");
                for (int i = 0; i < Math.min(lv.size(), 8); i++) {
                    Map<String, Object> l = lv.get(i);
                    sb.append(String.valueOf(l.get("start_date"))).append("(").append(nz(l.get("qjlx"))).append(")");
                    if (i < Math.min(lv.size(), 8) - 1) sb.append("、");
                }
            }
            sb.append("。\n");
        }
        Map<String, Object> prof = (Map<String, Object>) d.get("profile");
        if (prof != null && !prof.isEmpty()) {
            sb.append("档案：");
            if (nz(prof.get("sy")).length() > 0) sb.append("生源地").append(nz(prof.get("sy"))).append("；");
            if (nz(prof.get("jg")).length() > 0) sb.append("籍贯").append(nz(prof.get("jg"))).append("；");
            if (nz(prof.get("mz")).length() > 0) sb.append("民族").append(nz(prof.get("mz"))).append("；");
            if (nz(prof.get("zz")).length() > 0) sb.append("政治面貌").append(nz(prof.get("zz"))).append("；");
            String cs = nz(prof.get("cs"));
            if (cs.length() >= 4) sb.append("出生").append(cs, 0, 4).append("年；");
            String tx = nz(prof.get("tx"));
            if (tx.length() > 0) sb.append("家庭住址").append(tx, 0, Math.min(tx.length(), 18)).append("；");
            sb.append("\n");
        }
        if (nz(String.valueOf(d.get("dorm"))).length() > 0) sb.append("宿舍：").append(d.get("dorm")).append("。\n");
        sb.append("请生成深度分析。");
        return sb.toString();
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
                "xgh AS id, name, college, grade, level, no_return AS m1, late AS m2, (no_return * 2 + late) AS risk_score, (no_return + late) AS score");
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
                "xgh AS id, name, college, grade, level, fail_cnt AS m1, avg_score AS m2, courses AS score, (fail_cnt * 100 + (100 - COALESCE(avg_score, 100))) AS risk_score");
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
                sig + " SELECT xgh AS id, name, college, grade, level, drop_pct AS m1, no_return AS m2, (drop_pct + no_return * 10) AS score, (drop_pct + no_return * 10) AS risk_score FROM sig" +
                outer + " ORDER BY CASE level WHEN 'hi' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END, risk_score DESC LIMIT " + size + " OFFSET " + offset, h1s, h1e, h2s, e, ds, de);
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
                sig + " SELECT * FROM (SELECT " + selectCols + " FROM sig" + outer + ") ranked" +
                " ORDER BY CASE level WHEN 'hi' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END, COALESCE(risk_score, score) DESC LIMIT " + size + " OFFSET " + offset, params);
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
        String d = jt.queryForObject("SELECT (MAX(zdsdzzsj) + interval '8 hour')::date::text FROM usr_gxai.t_ykt_jkb", String.class);
        return d != null ? LocalDate.parse(d) : LocalDate.now().minusDays(1);
    }
}
