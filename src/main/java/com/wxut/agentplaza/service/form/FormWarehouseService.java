package com.wxut.agentplaza.service.form;

import com.wxut.agentplaza.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 智能填报-教师数据仓访问（usr_gxai.datav，只读）。
 * 查询口径与 mcp-wxut-main 教师线工具保持一致（CDC 快照排除 dke_op_type='d'）。
 * 数仓不可达时回落本地快照（3 名示范教师），保证流程可用并在 basis 中如实标注。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormWarehouseService {

    private final SysConfigService configService;

    private static final String DEFAULT_URL = "jdbc:postgresql://210.28.145.81:5431/datav";
    private static final String DEFAULT_USER = "usr_gxai";
    private static final String DEFAULT_PASS = "WXit@2026";
    private static final String SCHEMA = "usr_gxai.";

    /** 失败降级窗口：60s 内不再反复连数仓 */
    private volatile long degradedUntil = 0L;

    private static final Map<String, String> ZC_INFER = new LinkedHashMap<>();
    private static final Map<String, String> RYZT = new LinkedHashMap<>();
    private static final Map<String, String> GENDER = new LinkedHashMap<>();
    private static final Map<String, String> XL_INFER = new LinkedHashMap<>();
    private static final Map<String, String> XW_INFER = new LinkedHashMap<>();
    private static final Map<String, String> KHJG_INFER = new LinkedHashMap<>();

    static {
        ZC_INFER.put("011", "教授"); ZC_INFER.put("012", "副教授"); ZC_INFER.put("013", "讲师");
        ZC_INFER.put("014", "助教"); ZC_INFER.put("082", "高级工程师"); ZC_INFER.put("083", "工程师");
        RYZT.put("on", "在岗"); RYZT.put("quit", "离职"); RYZT.put("retire", "退休");
        GENDER.put("1", "男"); GENDER.put("2", "女");
        XL_INFER.put("11", "博士研究生"); XL_INFER.put("12", "硕士研究生");
        XL_INFER.put("21", "大学本科"); XL_INFER.put("31", "大学专科");
        XW_INFER.put("1", "博士"); XW_INFER.put("2", "硕士"); XW_INFER.put("3", "学士");
        KHJG_INFER.put("01", "优秀"); KHJG_INFER.put("02", "合格"); KHJG_INFER.put("03", "基本合格");
    }

    private String cfg(String key, String dft) {
        try {
            String v = configService.getByKey(key).getConfigValue();
            return (v == null || v.trim().isEmpty()) ? dft : v.trim();
        } catch (Exception e) {
            return dft;
        }
    }

    private boolean degraded() {
        return System.currentTimeMillis() < degradedUntil;
    }

    private Connection conn() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", cfg("formagent.warehouse.user", DEFAULT_USER));
        p.setProperty("password", cfg("formagent.warehouse.password", DEFAULT_PASS));
        p.setProperty("options", "-c default_transaction_read_only=on");
        Connection c = DriverManager.getConnection(cfg("formagent.warehouse.url", DEFAULT_URL), p);
        try (PreparedStatement st = c.prepareStatement("SET statement_timeout = '8s'")) {
            st.execute();
        }
        return c;
    }

    private static String s(ResultSet rs, String col) throws Exception {
        String v = rs.getString(col);
        return v == null ? "" : v.trim();
    }

    // ================= 对外 API =================

    /** 按姓名/工号检索教师（返回列表：zgh/xm/gender/zc/status/关键字段摘要） */
    public List<Map<String, Object>> searchTeachers(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return demoSearch("");
        if (degraded()) return demoSearch(kw);
        try (Connection c = conn()) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT zgh, xm, xbdm, nl, zyjszwdm, ryzt FROM " + SCHEMA + "t_jzg " +
                    "WHERE dke_op_type <> 'd' AND (zgh LIKE ? OR xm LIKE ?) " +
                    "ORDER BY (ryzt='on') DESC, zgh LIMIT 20");
            ps.setString(1, "%" + kw + "%");
            ps.setString(2, "%" + kw + "%");
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("zgh", s(rs, "zgh"));
                m.put("name", s(rs, "xm"));
                m.put("gender", GENDER.getOrDefault(s(rs, "xbdm"), s(rs, "xbdm")));
                m.put("age", (Object) rs.getObject("nl"));
                m.put("zc", zcLabel(s(rs, "zyjszwdm")));
                m.put("status", RYZT.getOrDefault(s(rs, "ryzt"), s(rs, "ryzt")));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            markDegraded(e);
            return demoSearch(kw);
        }
    }

    /** 教师 360 档案：返回以规范字段 key 索引的事实数据（含叙述所需的原始事实） */
    public Map<String, Object> getProfile(String zgh, String name) {
        if (zgh == null || zgh.trim().isEmpty()) zgh = "";
        if (degraded()) return demoProfile(zgh, name);
        try (Connection c = conn()) {
            String id = zgh;
            String nm = name == null ? "" : name.trim();
            if (id.isEmpty()) {
                PreparedStatement ps = c.prepareStatement(
                        "SELECT zgh FROM " + SCHEMA + "t_jzg WHERE dke_op_type <> 'd' AND xm = ? " +
                        "ORDER BY (ryzt='on') DESC LIMIT 2");
                ps.setString(1, nm);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalStateException("未找到姓名为「" + nm + "」的教职工");
                id = s(rs, "zgh");
                if (rs.next()) throw new IllegalStateException("姓名「" + nm + "」命中多名教职工，请用工号精确指定");
            }
            return loadProfile(c, id);
        } catch (Exception e) {
            markDegraded(e);
            return demoProfile(zgh, name);
        }
    }

    private Map<String, Object> loadProfile(Connection c, String zgh) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("zgh", zgh);
        p.put("_fallback", false);
        // 主表
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT xm, xbdm, nl, csrq::date csrq, mzdm, zyjszwdm, ryzt, cjgzrq::date cjgzrq " +
                "FROM " + SCHEMA + "t_jzg WHERE zgh = ? AND dke_op_type <> 'd' LIMIT 1")) {
            ps.setString(1, zgh);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalStateException("未找到工号 " + zgh);
            p.put("name", s(rs, "xm"));
            p.put("gender", GENDER.getOrDefault(s(rs, "xbdm"), s(rs, "xbdm")));
            p.put("age", (Object) rs.getObject("nl"));
            java.sql.Date birth = rs.getDate("csrq");
            p.put("birth", birth == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM").format(birth.toLocalDate()));
            p.put("mz", "01".equals(s(rs, "mzdm")) ? "汉族" : (s(rs, "mzdm").isEmpty() ? "" : "代码" + s(rs, "mzdm")));
            p.put("zc", zcLabel(s(rs, "zyjszwdm")));
            p.put("status", RYZT.getOrDefault(s(rs, "ryzt"), s(rs, "ryzt")));
            java.sql.Date since = rs.getDate("cjgzrq");
            p.put("since", since == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM").format(since.toLocalDate()));
        }
        // 学历学位（最高学历 = 学历码最小）
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT xl, xwm, sxzy, bsyxxhdw, jssj::date jssj FROM " + SCHEMA + "t_jzg_xlxw " +
                "WHERE jsgh = ? AND dke_op_type <> 'd' AND xl IS NOT NULL ORDER BY xl ASC LIMIT 1")) {
            ps.setString(1, zgh);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                p.put("xl", XL_INFER.getOrDefault(s(rs, "xl"), "代码" + s(rs, "xl")));
                p.put("xw", XW_INFER.getOrDefault(s(rs, "xwm"), s(rs, "xwm")));
                p.put("zy", s(rs, "sxzy"));
                p.put("xx", s(rs, "bsyxxhdw"));
                java.sql.Date d = rs.getDate("jssj");
                p.put("xwTime", d == null ? "" : DateTimeFormatter.ofPattern("yyyy-MM").format(d.toLocalDate()));
            }
        }
        String nm = (String) p.get("name");
        // 论文
        p.put("lwF", count(c, "SELECT count(*) FROM " + SCHEMA + "t_kyxt_lw WHERE dyzzgh = ? AND dke_op_type <> 'd'", zgh));
        p.put("lwA", count(c, "SELECT count(*) FROM " + SCHEMA + "t_kyxt_lw l WHERE l.dke_op_type <> 'd' AND " +
                "(l.dyzzgh = ? OR EXISTS (SELECT 1 FROM " + SCHEMA + "t_kyxt_kylwzz a WHERE a.lwbh = l.lwbh AND a.zzxm = ?))", zgh, nm));
        List<String> lws = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lwmc, kwmc, fbsj::date fbsj FROM " + SCHEMA + "t_kyxt_lw " +
                "WHERE dyzzgh = ? AND dke_op_type <> 'd' ORDER BY fbsj DESC NULLS LAST LIMIT 3")) {
            ps.setString(1, zgh);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String t = s(rs, "lwmc");
                if (!s(rs, "kwmc").isEmpty()) t += " · 《" + s(rs, "kwmc") + "》";
                java.sql.Date d = rs.getDate("fbsj");
                if (d != null) t += " · " + DateTimeFormatter.ofPattern("yyyy").format(d.toLocalDate());
                lws.add(t);
            }
        }
        p.put("lws1", lws.size() > 0 ? lws.get(0) : "");
        p.put("lws2", lws.size() > 1 ? lws.get(1) : "");
        p.put("lws3", lws.size() > 2 ? lws.get(2) : "");
        // 专利
        p.put("zlF", count(c, "SELECT count(*) FROM " + SCHEMA + "t_kyxt_zl WHERE dke_op_type <> 'd' AND " +
                "(dyzzjgh = ? OR (dyzz = ? AND COALESCE(dyzzjgh,'') = ''))", zgh, nm));
        p.put("zlJ", count(c, "SELECT count(DISTINCT c.lwbh) FROM " + SCHEMA + "t_kyxt_zlcyxx c " +
                "JOIN " + SCHEMA + "t_kyxt_zl z ON z.zlid = c.lwbh AND z.dke_op_type <> 'd' " +
                "WHERE c.dke_op_type <> 'd' AND c.zzzgh = ?", zgh));
        // 横向
        p.put("hxF", count(c, "SELECT count(*) FROM " + SCHEMA + "t_kyxt_hxxm WHERE fzrxm = ? AND dke_op_type <> 'd'", nm));
        p.put("hxJ", count(c, "SELECT count(DISTINCT r.htbh) FROM " + SCHEMA + "t_kyxt_hxxmry r " +
                "JOIN " + SCHEMA + "t_kyxt_hxxm h ON h.id = r.htbh AND h.dke_op_type <> 'd' " +
                "WHERE r.dke_op_type <> 'd' AND (r.zgh = ? OR (r.cymc = ? AND COALESCE(r.zgh,'') = ''))", zgh, nm));
        // 纵向
        p.put("zxJ", count(c, "SELECT count(*) FROM " + SCHEMA + "t_kyxt_zxxmry r WHERE r.zzgh = ? AND r.dke_op_type <> 'd' " +
                "AND EXISTS (SELECT 1 FROM " + SCHEMA + "t_kyxt_zxxm m WHERE m.xmbh = r.xmbh AND m.dke_op_type <> 'd')", zgh));
        // 经费到账（负责的横向项目）
        p.put("jf", scalar(c, "SELECT COALESCE(SUM(d.dzje), 0) FROM " + SCHEMA + "t_kyxt_xmjf_dz d " +
                "WHERE d.dke_op_type <> 'd' AND d.xmbh IN " +
                "(SELECT id FROM " + SCHEMA + "t_kyxt_hxxm WHERE fzrxm = ? AND dke_op_type <> 'd')", nm));
        // 教学评价（近三年）
        p.put("pjS", avgScore(c, "SELECT xpf FROM " + SCHEMA + "t_zkxt_xspj WHERE jgh = ? AND xnxq >= to_char(now() - interval '3 year', 'YYYY')", zgh));
        p.put("pjD", avgScore(c, "SELECT pjzf FROM " + SCHEMA + "t_zkxt_dd WHERE jgh = ? AND skrq >= now() - interval '3 year'", zgh));
        p.put("pjT", avgScore(c, "SELECT pjzf FROM " + SCHEMA + "t_zkxt_tx WHERE jgh = ? AND skrq >= now() - interval '3 year'", zgh));
        // 考核
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT khnd, khjg FROM " + SCHEMA + "t_jzg_kh WHERE jsgh = ? AND dke_op_type <> 'd' " +
                "ORDER BY khnd DESC LIMIT 10")) {
            ps.setString(1, zgh);
            ResultSet rs = ps.executeQuery();
            Map<String, String> kh = new LinkedHashMap<>();
            while (rs.next()) {
                String y = s(rs, "khnd");
                if (y.length() >= 4 && !kh.containsKey(y.substring(0, 4))) {
                    kh.put(y.substring(0, 4), khjgLabel(s(rs, "khjg")));
                }
            }
            p.put("kh2024", kh.getOrDefault("2024", ""));
            p.put("kh2023", kh.getOrDefault("2023", ""));
            p.put("kh2022", kh.getOrDefault("2022", ""));
        }
        p.put("cur", "");
        p.put("jg", "");
        return p;
    }

    private long count(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private String scalar(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getObject(1) != null ? String.valueOf(rs.getBigDecimal(1).stripTrailingZeros().toPlainString()) : "0";
        }
    }

    /** 评分列多为 varchar 且可能脏：正则过滤后求均值 */
    private String avgScore(Connection c, String sql, Object... args) throws Exception {
        List<Double> vals = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null && v.trim().matches("^[0-9]+(\\.[0-9]+)?$")) {
                    double d = Double.parseDouble(v.trim());
                    if (d >= 0 && d <= 100) vals.add(d);
                }
            }
        }
        if (vals.isEmpty()) return "";
        double avg = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format("%.1f", avg);
    }

    private static String zcLabel(String code) {
        if (code == null || code.isEmpty()) return "未填报";
        return ZC_INFER.getOrDefault(code, "代码" + code);
    }

    private static String khjgLabel(String code) {
        if (code == null || code.isEmpty()) return "未填";
        return KHJG_INFER.getOrDefault(code, code);
    }

    private void markDegraded(Exception e) {
        degradedUntil = System.currentTimeMillis() + 60_000L;
        log.warn("智能填报：数仓不可达，60s 内使用本地快照。原因：{}", e.getMessage());
    }

    // ================= 本地快照回落 =================

    private List<Map<String, Object>> demoSearch(String kw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : demoProfiles()) {
            String nm = String.valueOf(p.get("name"));
            String zg = String.valueOf(p.get("zgh"));
            if (kw == null || kw.isEmpty() || nm.contains(kw) || zg.contains(kw)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("zgh", zg); m.put("name", nm);
                m.put("gender", p.get("gender")); m.put("age", p.get("age"));
                m.put("zc", p.get("zc")); m.put("status", "在岗");
                out.add(m);
            }
        }
        return out;
    }

    private Map<String, Object> demoProfile(String zgh, String name) {
        for (Map<String, Object> p : demoProfiles()) {
            boolean idHit = zgh != null && !zgh.trim().isEmpty() && zgh.trim().equals(p.get("zgh"));
            boolean nmHit = name != null && !name.trim().isEmpty() && name.trim().equals(p.get("name"));
            if (idHit || (zgh == null || zgh.trim().isEmpty()) && nmHit) {
                Map<String, Object> copy = new LinkedHashMap<>(p);
                copy.put("_fallback", true);
                return copy;
            }
        }
        Map<String, Object> p = new LinkedHashMap<>(demoProfiles().get(0));
        p.put("_fallback", true);
        return p;
    }

    private List<Map<String, Object>> demoProfiles() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(profile(new Object[][]{
                {"zgh", "20193058"}, {"name", "陈立群"}, {"gender", "男"}, {"age", 47}, {"birth", "1978-03"},
                {"mz", "汉族"}, {"zc", "教授"}, {"status", "在岗"}, {"since", "2003-07"}, {"cur", ""},
                {"xl", "博士研究生"}, {"xw", "工学博士"}, {"xx", "上海交通大学"}, {"zy", "机械制造及其自动化"}, {"xwTime", "2009-06"},
                {"lwF", 23}, {"lwA", 31}, {"zlF", 8}, {"zlJ", 5}, {"hxF", 4}, {"hxJ", 3}, {"zxJ", 6},
                {"jf", "187.6"}, {"rjzz", 3}, {"zz", 1}, {"jg", ""},
                {"pjS", "94.2"}, {"pjD", "92.0"}, {"pjT", "91.5"},
                {"kh2024", "优秀"}, {"kh2023", "合格"}, {"kh2022", "合格"},
                {"lws1", "基于数字孪生的复杂曲面加工误差自适应补偿方法 · 《机械工程学报》 · 2025"},
                {"lws2", "面向薄壁件高速铣削的颤振在线抑制策略 · 《中国机械工程》 · 2024"},
                {"lws3", "Digital-twin driven process planning for complex thin-walled parts · 《Robotics and Computer-Integrated Manufacturing》 · 2024"}}));
        list.add(profile(new Object[][]{
                {"zgh", "20210773"}, {"name", "林晚晴"}, {"gender", "女"}, {"age", 37}, {"birth", "1989-11"},
                {"mz", "汉族"}, {"zc", "副教授"}, {"status", "在岗"}, {"since", "2015-07"}, {"cur", "系主任助理"},
                {"xl", "博士研究生"}, {"xw", "工学博士"}, {"xx", "东南大学"}, {"zy", "信息与通信工程"}, {"xwTime", "2018-06"},
                {"lwF", 11}, {"lwA", 15}, {"zlF", 3}, {"zlJ", 2}, {"hxF", 1}, {"hxJ", 2}, {"zxJ", 3},
                {"jf", "42.3"}, {"rjzz", 2}, {"zz", 0}, {"jg", ""},
                {"pjS", "96.8"}, {"pjD", "93.5"}, {"pjT", "92.0"},
                {"kh2024", "合格"}, {"kh2023", "优秀"}, {"kh2022", "合格"},
                {"lws1", "工业物联网边缘计算资源协同调度方法 · 《通信学报》 · 2025"},
                {"lws2", "面向智能产线的多源异构数据融合框架 · 《仪器仪表学报》 · 2024"},
                {"lws3", "Edge intelligence for smart factory: a survey · 《Journal of Industrial Information Integration》 · 2024"}}));
        list.add(profile(new Object[][]{
                {"zgh", "20150412"}, {"name", "周明远"}, {"gender", "男"}, {"age", 41}, {"birth", "1985-06"},
                {"mz", "汉族"}, {"zc", "讲师"}, {"status", "在岗"}, {"since", "2012-07"}, {"cur", ""},
                {"xl", "硕士研究生"}, {"xw", "管理学硕士"}, {"xx", "江南大学"}, {"zy", "工商管理"}, {"xwTime", "2012-06"},
                {"lwF", 6}, {"lwA", 7}, {"zlF", 2}, {"zlJ", 0}, {"hxF", 0}, {"hxJ", 1}, {"zxJ", 1},
                {"jf", "8.5"}, {"rjzz", 0}, {"zz", 0}, {"jg", ""},
                {"pjS", "91.4"}, {"pjD", "89.0"}, {"pjT", "90.0"},
                {"kh2024", "合格"}, {"kh2023", "合格"}, {"kh2022", "基本合格"},
                {"lws1", "产教融合背景下智能制造管理人才培养路径研究 · 《职教论坛》 · 2024"},
                {"lws2", "职业院校企业管理课程混合式教学设计 · 《教育与职业》 · 2023"},
                {"lws3", ""}}));
        return list;
    }

    private static Map<String, Object> profile(Object[][] kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object[] pair : kv) m.put((String) pair[0], pair[1]);
        return m;
    }
}
