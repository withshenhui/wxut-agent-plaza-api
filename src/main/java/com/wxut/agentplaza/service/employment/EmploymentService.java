package com.wxut.agentplaza.service.employment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wxut.agentplaza.service.SysConfigService;
import com.wxut.agentplaza.service.form.FormLlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 智慧就业：学生画像（数仓只读）+ 就业意向 + 岗位匹配推荐。
 * 匹配三步：规则硬过滤（城市/学历/专业关键词）→ LLM 精排（分+理由+短板）→ top N。
 * 画像只用「专业/年级/成绩」，不触碰消费/门禁/请假/处分等行为数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmploymentService {

    private final SysConfigService configService;
    private final FormLlmService llm;
    private final javax.sql.DataSource dataSource;

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Pattern MAJOR_SUFFIX = Pattern.compile("_[^_]*$");

    /* ---------------- 数仓只读画像 ---------------- */

    private Connection pg() throws Exception {
        String url = "jdbc:postgresql://210.28.144.245:5432/postgres";
        String user = "postgres", pwd = "";
        try { url = nz(configService.getByKey("employment.warehouse.url").getConfigValue(), url); } catch (Exception ignore) {}
        try { user = nz(configService.getByKey("employment.warehouse.user").getConfigValue(), user); } catch (Exception ignore) {}
        try { pwd = nz(configService.getByKey("employment.warehouse.password").getConfigValue(), pwd); } catch (Exception ignore) {}
        Class.forName("org.postgresql.Driver"); // 共享容器下必须显式加载
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", user);
        p.setProperty("password", pwd);
        p.setProperty("options", "-c default_transaction_read_only=on"); // 只读铁律
        Connection c = DriverManager.getConnection(url, p);
        try (Statement st = c.createStatement()) {
            st.execute("SET search_path TO public");
            st.execute("SET statement_timeout = '8s'");
        }
        return c;
    }

    /** 按学号取学生画像：遍历各学院学生基本信息表；只取非敏感字段 */
    public Map<String, Object> profile(String studentNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (studentNo == null || studentNo.isEmpty()) return out;
        try (Connection c = pg()) {
            List<String> tables = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE '学生基本信息%'")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) tables.add(rs.getString(1));
            }
            for (String tb : tables) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT \"学号\",\"姓名\",\"性别\",\"年级\",\"学院\",\"班级\",\"专业名称\",\"生源地\" FROM \"" + tb + "\" WHERE \"学号\"=? LIMIT 1")) {
                    ps.setString(1, studentNo);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        out.put("studentNo", rs.getString(1));
                        out.put("name", rs.getString(2));
                        out.put("gender", rs.getString(3));
                        out.put("grade", rs.getString(4));
                        out.put("college", rs.getString(5));
                        out.put("className", rs.getString(6));
                        out.put("major", cleanMajor(rs.getString(7)));
                        out.put("origin", rs.getString(8));
                        break;
                    }
                } catch (Exception ignore) {}
            }
            // 成绩摘要（GPA 均值/挂科数，表名 学生成绩信息_学院；字段以实际为准，容错）
            if (out.containsKey("college")) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=? ")) {
                    ps.setString(1, "学生成绩信息_" + out.get("college"));
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0) {
                        try (PreparedStatement ps2 = c.prepareStatement(
                                "SELECT COUNT(*), AVG(CASE WHEN \"成绩\" ~ '^[0-9.]+$' THEN \"成绩\"::numeric ELSE NULL END) FROM \"学生成绩信息_" + out.get("college") + "\" WHERE \"学号\"=?")) {
                            ps2.setString(1, studentNo);
                            ResultSet r2 = ps2.executeQuery();
                            if (r2.next()) {
                                out.put("courseCount", r2.getInt(1));
                                double avg = r2.getDouble(2);
                                if (!r2.wasNull() && avg > 0) out.put("avgScore", Math.round(avg * 10) / 10.0);
                            }
                        } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.warn("就业：画像查询失败 {}: {}", studentNo, e.getMessage());
        }
        return out;
    }

    /** 「机械制造及自动化_高中」→「机械制造及自动化」 */
    static String cleanMajor(String m) {
        if (m == null) return null;
        return MAJOR_SUFFIX.matcher(m.trim()).replaceAll("");
    }

    /* ---------------- 意向 ---------------- */

    public Map<String, Object> getIntent(String studentNo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT city, industry, job_keywords, salary_expect, job_type FROM student_intent WHERE student_no=?")) {
            ps.setString(1, studentNo);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("city", rs.getString(1));
                m.put("industry", rs.getString(2));
                m.put("jobKeywords", rs.getString(3));
                m.put("salaryExpect", rs.getObject(4));
                m.put("jobType", rs.getString(5));
                return m;
            }
        } catch (Exception e) { log.warn("就业：读取意向失败 {}: {}", studentNo, e.getMessage()); }
        return null;
    }

    public void saveIntent(String studentNo, Map<String, Object> body) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO student_intent (student_no, city, industry, job_keywords, salary_expect, job_type, updated_at) VALUES (?,?,?,?,?,?,NOW()) " +
                             "ON DUPLICATE KEY UPDATE city=VALUES(city), industry=VALUES(industry), job_keywords=VALUES(job_keywords), salary_expect=VALUES(salary_expect), job_type=VALUES(job_type), updated_at=NOW()")) {
            ps.setString(1, studentNo);
            ps.setString(2, str(body.get("city")));
            ps.setString(3, str(body.get("industry")));
            ps.setString(4, str(body.get("jobKeywords")));
            Object se = body.get("salaryExpect");
            if (se == null || "".equals(se)) ps.setNull(5, Types.INTEGER); else ps.setInt(5, Integer.parseInt(String.valueOf(se)));
            String jt = str(body.get("jobType"));
            ps.setString(6, jt == null || jt.isEmpty() ? "any" : jt);
            ps.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("保存意向失败：" + e.getMessage()); }
    }

    /* ---------------- 匹配推荐 ---------------- */

    /** 规则硬过滤：城市 + 专业关键词宽匹配 + 排学历过高；返回候选（上限 40） */
    public List<Map<String, Object>> filter(String major, Map<String, Object> intent) {
        List<String> cityList = splitList(str(intent == null ? null : intent.get("city")));
        List<String> kw = majorKeywords(major);
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, company, city, salary_text, edu_req, major_hint, skills, hire_count, url, source " +
                        "FROM job_post WHERE status='active' AND edu_req NOT LIKE '%博士%'");
        List<Object> args = new ArrayList<>();
        if (!cityList.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < cityList.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("city LIKE ?");
                args.add("%" + cityList.get(i) + "%");
            }
            sql.append(" OR city LIKE '%江苏省%')"); // 省级兜底
        }
        // 排序：意向城市命中 → 专业关键词命中 → 招聘人数/新鲜度（专业不做硬过滤，交 LLM 精排判断匹配度）
        StringBuilder order = new StringBuilder();
        if (!cityList.isEmpty()) {
            order.append("(CASE WHEN ");
            for (int i = 0; i < cityList.size(); i++) {
                if (i > 0) order.append(" OR ");
                order.append("city LIKE ?");
                args.add("%" + cityList.get(i) + "%");
            }
            order.append(" THEN 1 ELSE 0 END) DESC, ");
        }
        if (!kw.isEmpty()) {
            order.append("(CASE WHEN ");
            for (int i = 0; i < kw.size(); i++) {
                if (i > 0) order.append(" OR ");
                order.append("major_hint LIKE ? OR title LIKE ?");
                args.add("%" + kw.get(i) + "%");
                args.add("%" + kw.get(i) + "%");
            }
            order.append(" THEN 1 ELSE 0 END) DESC, ");
        }
        sql.append(" ORDER BY ").append(order).append("(hire_count IS NULL), hire_count DESC, publish_date DESC LIMIT 40");
        return queryJobs(sql.toString(), args);
    }

    private List<Map<String, Object>> queryJobs(String sql, List<Object> args) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        } catch (Exception e) { log.warn("就业：岗位查询失败：{}", e.getMessage()); }
        return out;
    }

    /** 完整推荐：过滤 → LLM 精排（失败降级规则分） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recommend(String studentNo) {
        Map<String, Object> profile = profile(studentNo);
        Map<String, Object> intent = getIntent(studentNo);
        String major = str(profile.get("major"));
        List<Map<String, Object>> cand = filter(major, intent);
        if (cand.isEmpty()) return cand;
        List<Map<String, Object>> top = cand.size() > 20 ? new ArrayList<>(cand.subList(0, 20)) : cand;
        try {
            String ranked = llmRank(profile, intent, top);
            if (ranked != null && !ranked.isEmpty()) {
                List<Map<String, Object>> merged = new ArrayList<>();
                JsonNode arr = OM.readTree(ranked);
                if (arr.isArray() && arr.size() > 0) {
                    Map<Long, Map<String, Object>> byId = new HashMap<>();
                    for (Map<String, Object> j : top) byId.put(((Number) j.get("id")).longValue(), j);
                    for (JsonNode n : arr) {
                        long jid = n.path("jobId").asLong();
                        Map<String, Object> job = byId.get(jid);
                        if (job == null) continue;
                        Map<String, Object> row = new LinkedHashMap<>(job);
                        row.put("score", n.path("score").asInt(70));
                        row.put("reason", n.path("reason").asText(""));
                        row.put("gaps", n.path("gaps").asText(""));
                        merged.add(row);
                        if (merged.size() >= 10) break;
                    }
                    if (!merged.isEmpty()) return merged;
                }
            }
        } catch (Exception e) {
            log.warn("就业：LLM 精排失败，降级规则分：{}", e.getMessage());
        }
        return ruleRank(top, major, intent);
    }

    private String llmRank(Map<String, Object> profile, Map<String, Object> intent, List<Map<String, Object>> cand) throws Exception {
        String sys = "你是校园就业匹配助手。依据学生画像与就业意向，对候选岗位逐一给出匹配分(0-100)、推荐理由(1-2句，须引用学生本人的专业/年级/成绩等事实)、短板提示(具体可补的能力)。"
                + "硬性要求：岗位学历要求明显高于学生层次时给低分；理由不得编造学生数据中不存在的信息。只输出 JSON 数组，形如 [{\"jobId\":1,\"score\":88,\"reason\":\"...\",\"gaps\":\"...\"}]，不要任何其他文字。";
        String user = "学生画像：" + OM.writeValueAsString(profile)
                + "\n就业意向：" + OM.writeValueAsString(intent == null ? Collections.emptyMap() : intent)
                + "\n候选岗位：" + OM.writeValueAsString(cand);
        String raw = llm.chat(sys, user);
        return stripFence(raw);
    }

    /** 去掉 ```json 代码块包裹 */
    static String stripFence(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int i = t.indexOf('\n');
            if (i > 0) t = t.substring(i + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        int a = t.indexOf('['), b = t.lastIndexOf(']');
        if (a >= 0 && b > a) t = t.substring(a, b + 1);
        return t.trim();
    }

    /** 规则降级：专业命中 + 城市命中 + 招聘人数 */
    private List<Map<String, Object>> ruleRank(List<Map<String, Object>> cand, String major, Map<String, Object> intent) {
        List<String> kw = majorKeywords(major);
        List<String> cityList = splitList(str(intent == null ? null : intent.get("city")));
        for (Map<String, Object> j : cand) {
            int score = 60;
            String mh = str(j.get("major_hint")) + str(j.get("title"));
            for (String k : kw) if (mh.contains(k)) { score += 12; break; }
            String city = str(j.get("city"));
            for (String ct : cityList) if (city.contains(ct)) { score += 15; break; }
            Object hc = j.get("hire_count");
            if (hc != null) { try { score += Math.min(8, Integer.parseInt(String.valueOf(hc))); } catch (Exception ignore) {} }
            j.put("score", Math.min(95, score));
            j.put("reason", "按专业方向与意向城市规则匹配（大模型精排暂不可用，结果仅供参考）");
            j.put("gaps", "");
        }
        cand.sort((a, b) -> ((Integer) b.get("score")) - ((Integer) a.get("score")));
        return cand.size() > 10 ? new ArrayList<>(cand.subList(0, 10)) : cand;
    }

    /* ---------------- 教师端聚合 ---------------- */

    public Map<String, Object> overview(String collegeFilter) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            // 岗位总量 / 来源分布 / 城市分布 TOP / 供需（意向方向 vs 供给方向——一期用专业关键词粗口径）
            out.put("jobTotal", one(c, "SELECT COUNT(*) FROM job_post WHERE status='active'"));
            out.put("jobByCity", rows(c, "SELECT city, COUNT(*) n FROM job_post WHERE status='active' AND city IS NOT NULL GROUP BY city ORDER BY n DESC LIMIT 10"));
            out.put("intentTotal", one(c, "SELECT COUNT(*) FROM student_intent"));
            out.put("intentByCity", rows(c, "SELECT city, COUNT(*) n FROM student_intent WHERE city IS NOT NULL AND city<>'' AND city<>'[]' GROUP BY city ORDER BY n DESC LIMIT 10"));
            out.put("recTotal", one(c, "SELECT COUNT(*) FROM rec_result"));
        } catch (Exception e) { log.warn("就业：看板聚合失败：{}", e.getMessage()); }
        return out;
    }

    private long one(Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) { return 0L; }
    }

    private List<Map<String, Object>> rows(Connection c, String sql) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        } catch (Exception ignore) {}
        return out;
    }

    /* ---------------- 工具 ---------------- */

    /** 专业 → 关键词：按连接词切段 + 前缀词（"机械制造及自动化"→["机械制造","自动化","机械"]） */
    static List<String> majorKeywords(String major) {
        List<String> out = new ArrayList<>();
        if (major == null || major.isEmpty()) return out;
        String m = major.replaceAll("[()（）]", "");
        for (String seg : m.split("[、,，/\\s及与和]+")) {
            if (seg.length() >= 2) out.add(seg);
        }
        if (!out.isEmpty()) {
            String first = out.get(0);
            if (first.length() > 2) out.add(first.substring(0, 2)); // 前缀词（"机械"）
        }
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty() || "[]".equals(s)) return out;
        String t = s.replace("[", "").replace("]", "").replace("\"", "");
        for (String x : t.split("[,，、\\s]+")) if (!x.isEmpty() && !"不限".equals(x)) out.add(x);
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String nz(String v, String d) { return v == null || v.isEmpty() ? d : v; }
}
