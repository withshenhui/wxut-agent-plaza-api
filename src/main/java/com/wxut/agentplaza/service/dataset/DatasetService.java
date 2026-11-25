package com.wxut.agentplaza.service.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI 数据工场一期：数据接入 → 清洗 → 标注(LabelStudio 交换) → 数据集版本 → 导出。
 * 存储：91 库 agent_plaza 的 ds_* 六表（轻量 JDBC，避免过多实体样板）。
 * 权限：由控制器层 gate（data.view / data.manage）。
 */
@Slf4j
@Service
public class DatasetService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.wxut.agentplaza.service.kb.KbTextExtractor textExtractor;

    @Autowired
    private com.wxut.agentplaza.service.SysConfigService configService;

    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_ITEMS = 50000;
    private static final Pattern RE_IDCARD = Pattern.compile("(?<!\\d)(\\d{6}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])(?!\\d)");
    private static final Pattern RE_PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /* ===================== 数据集 ===================== */

    public List<Map<String, Object>> list(Long ownerUserId, boolean all) {
        String sql = "SELECT id,name,description,status,item_count,cleaned_count,annotated_count,created_at,updated_at FROM ds_dataset"
                + (all ? "" : " WHERE owner_user_id=?") + " ORDER BY id DESC";
        try (Connection c = dataSource.getConnection()) {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (!all) ps.setLong(1, ownerUserId);
                try (ResultSet rs = ps.executeQuery()) { out = mapRows(rs); }
            }
            return out;
        } catch (Exception e) { log.warn("dataset list: {}", e.getMessage()); return new ArrayList<>(); }
    }

    public long create(Long ownerUserId, String name, String description, String sourceType) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ds_dataset(name,description,owner_user_id,source_type,status) VALUES(?,?,?,?, 'active')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name == null || name.trim().isEmpty() ? "未命名数据集" : name.trim());
            ps.setString(2, description);
            ps.setLong(3, ownerUserId);
            ps.setString(4, sourceType == null ? "file" : sourceType);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0L; }
        }
    }

    public void delete(long id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            for (String t : new String[]{"ds_item", "ds_clean_rule", "ds_annot_task", "ds_version"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE dataset_id=?")) { ps.setLong(1, id); ps.executeUpdate(); }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ds_dataset WHERE id=?")) { ps.setLong(1, id); ps.executeUpdate(); }
            c.commit();
        } catch (Exception e) { log.warn("dataset delete: {}", e.getMessage()); }
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            Map<String, Object> ds = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM ds_dataset WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) { List<Map<String, Object>> r = mapRows(rs); if (!r.isEmpty()) ds = r.get(0); }
            }
            out.put("dataset", ds);
            out.put("rules", queryList(c, "SELECT * FROM ds_clean_rule WHERE dataset_id=? ORDER BY id", id));
            out.put("annotTasks", queryList(c, "SELECT id,name,template,status,total,done,created_at FROM ds_annot_task WHERE dataset_id=? ORDER BY id DESC", id));
            out.put("versions", queryList(c, "SELECT id,version,name,train_ratio,item_count,format,lineage,created_at FROM ds_version WHERE dataset_id=? ORDER BY version DESC", id));
            out.put("labelstudio", cfg("labelstudio.base_url", ""));
            out.put("labelstudioReady", !cfg("labelstudio.base_url", "").isEmpty() && !cfg("labelstudio.token", "").isEmpty());
        } catch (Exception e) { log.warn("dataset detail: {}", e.getMessage()); }
        return out;
    }

    /* ===================== 接入 ===================== */

    public Map<String, Object> ingestFile(long datasetId, MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "data" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        List<String> contents = new ArrayList<>();
        if ("pdf".equals(ext) || "docx".equals(ext) || "doc".equals(ext)) {
            // 复用知识库文本抽取（POI + PDFBox）：按段落/分页切成样本
            String text = textExtractor.extract(file.getInputStream(), ext);
            for (String para : text.split("\\n+")) {
                String t = para.trim();
                if (!t.isEmpty()) { contents.add(t); if (contents.size() >= MAX_ITEMS) break; }
            }
        } else if ("jsonl".equals(ext)) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null && contents.size() < MAX_ITEMS) { line = line.trim(); if (!line.isEmpty()) contents.add(line); }
            }
        } else if ("json".equals(ext)) {
            JsonNode root = OM.readTree(file.getInputStream());
            if (root.isArray()) { for (JsonNode n : root) { if (contents.size() >= MAX_ITEMS) break; contents.add(n.isTextual() ? n.asText() : OM.writeValueAsString(n)); } }
            else contents.add(OM.writeValueAsString(root));
        } else { // csv / tsv / txt / 其它：按行
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null && contents.size() < MAX_ITEMS) { String t = line.trim(); if (!t.isEmpty()) contents.add(t); }
            }
        }
        int n = insertItems(datasetId, contents, "file");
        recomputeCounts(datasetId);
        Map<String, Object> out = new LinkedHashMap<>(); out.put("parsed", n); out.put("ext", ext);
        return out;
    }

    private int insertItems(long datasetId, List<String> contents, String sourceType) throws Exception {
        if (contents.isEmpty()) return 0;
        int seq0 = 0;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(seq),0) FROM ds_item WHERE dataset_id=?")) { ps.setLong(1, datasetId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) seq0 = rs.getInt(1); } }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ds_item(dataset_id,seq,raw_content,clean_status) VALUES(?,?,?, 'raw')")) {
                int i = 0;
                for (String s : contents) {
                    ps.setLong(1, datasetId); ps.setInt(2, ++seq0); ps.setString(3, s); ps.addBatch();
                    if (++i % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
        return contents.size();
    }

    /* ===================== 清洗 ===================== */

    public Map<String, Object> runClean(long datasetId, List<Map<String, String>> rules, Map<String, Object> thresholds) throws Exception {
        // 规则：dedupe / near_dedupe / mask_id / mask_phone / trim / lower / strip_html —— 未传则用默认全开
        Set<String> set = new LinkedHashSet<>();
        if (rules != null) for (Map<String, String> r : rules) { String t = r.get("ruleType"); if (t != null) set.add(t); }
        if (set.isEmpty()) set.addAll(Arrays.asList("trim", "dedupe", "mask_id", "mask_phone", "strip_html"));
        int minLen = 6, nearDupThreshold = 3;
        if (thresholds != null) {
            try { if (thresholds.get("minLen") != null) minLen = Integer.parseInt(String.valueOf(thresholds.get("minLen"))); } catch (Exception ignore) {}
            try { if (thresholds.get("nearDupThreshold") != null) nearDupThreshold = Integer.parseInt(String.valueOf(thresholds.get("nearDupThreshold"))); } catch (Exception ignore) {}
        }

        try (Connection c = dataSource.getConnection()) {
            // 记录规则
            try (PreparedStatement del = c.prepareStatement("DELETE FROM ds_clean_rule WHERE dataset_id=?")) { del.setLong(1, datasetId); del.executeUpdate(); }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO ds_clean_rule(dataset_id,rule_type,enabled) VALUES(?,?,1)")) {
                for (String r : set) { ins.setLong(1, datasetId); ins.setString(2, r); ins.addBatch(); }
                ins.executeBatch();
            }
            c.setAutoCommit(false);
            Set<String> seen = new HashSet<>();
            int cleaned = 0, dropped = 0, total = 0;
            Map<String, Integer> hits = new LinkedHashMap<>();
            for (String r : set) hits.put(r, 0);
            List<Map<String, Object>> samples = new ArrayList<>();
            int residualId = 0, residualPhone = 0;
            List<Object[]> updates = new ArrayList<>();
            long sumRaw = 0, sumClean = 0; int piiRaw = 0, dupRaw = 0, emptyRaw = 0, shortRaw = 0;
            Set<String> rawSeen = new HashSet<>(); int[] histRaw = new int[5], histClean = new int[5];
            Map<Long, List<Long>> nearBuckets = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id,raw_content FROM ds_item WHERE dataset_id=? ORDER BY seq")) {
                ps.setLong(1, datasetId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total++;
                        long iid = rs.getLong(1);
                        String raw = rs.getString(2) == null ? "" : rs.getString(2);
                        String rawT = raw.trim();
                        sumRaw += raw.length();
                        if (rawT.isEmpty()) emptyRaw++;
                        if (rawT.length() < minLen) shortRaw++;
                        if (RE_IDCARD.matcher(raw).find() || RE_PHONE.matcher(raw).find()) piiRaw++;
                        if (!rawSeen.add(rawT)) dupRaw++;
                        histRaw[lenBucket(rawT.length())]++;
                        String s = raw;
                        if (set.contains("trim")) { String t = s.trim(); if (!t.equals(s)) bump(hits, "trim"); s = t; }
                        if (set.contains("lower")) { String t = s.toLowerCase(); if (!t.equals(s)) bump(hits, "lower"); s = t; }
                        if (set.contains("strip_html")) { String t = s.replaceAll("(?is)<[^>]+>", " "); if (!t.equals(s)) bump(hits, "strip_html"); s = t; }
                        if (set.contains("mask_id")) { String t = maskRegex(s, RE_IDCARD, 4, 3); if (!t.equals(s)) bump(hits, "mask_id"); s = t; }
                        if (set.contains("mask_phone")) { String t = maskRegex(s, RE_PHONE, 3, 4); if (!t.equals(s)) bump(hits, "mask_phone"); s = t; }
                        String h = md5(s);
                        boolean dup = false;
                        if (set.contains("dedupe") && !seen.add(h)) { dup = true; bump(hits, "dedupe"); }
                        if (!dup && set.contains("near_dedupe") && s.trim().length() >= minLen) {
                            long fp = simhash(s); long key = fp >>> 48; boolean near = false;
                            for (long k = key - 1; k <= key + 1 && !near; k++) {
                                List<Long> bk = nearBuckets.get(k);
                                if (bk != null) for (long o : bk) { if (hamming(fp, o) <= nearDupThreshold) { near = true; break; } }
                            }
                            if (near) { dup = true; bump(hits, "near_dedupe"); }
                            else nearBuckets.computeIfAbsent(key, x -> new ArrayList<>()).add(fp);
                        }
                        if (dup) {
                            updates.add(new Object[]{iid, null, "dropped"}); dropped++;
                        } else {
                            if (RE_IDCARD.matcher(s).find()) residualId++;
                            if (RE_PHONE.matcher(s).find()) residualPhone++;
                            sumClean += s.length(); histClean[lenBucket(s.trim().length())]++;
                            updates.add(new Object[]{iid, s, "cleaned"}); cleaned++;
                            if (!s.equals(raw) && samples.size() < 20) {
                                Map<String, Object> sm = new LinkedHashMap<>();
                                sm.put("raw", raw.length() > 400 ? raw.substring(0, 400) : raw);
                                sm.put("clean", s.length() > 400 ? s.substring(0, 400) : s);
                                samples.add(sm);
                            }
                        }
                    }
                }
            }
            try (PreparedStatement up = c.prepareStatement("UPDATE ds_item SET clean_content=?,clean_status=?,content_hash=? WHERE id=?")) {
                int i = 0;
                for (Object[] u : updates) {
                    up.setString(1, (String) u[1]); up.setString(2, (String) u[2]); up.setString(3, md5((String) u[1])); up.setLong(4, (Long) u[0]);
                    up.addBatch();
                    if (++i % 500 == 0) up.executeBatch();
                }
                up.executeBatch();
            }
            c.commit();
            recomputeCounts(datasetId);
            List<Map<String, Object>> ruleReport = new ArrayList<>();
            for (String r : set) { Map<String, Object> m = new LinkedHashMap<>(); m.put("rule", r); m.put("hits", hits.get(r)); ruleReport.add(m); }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total); out.put("cleaned", cleaned); out.put("dropped", dropped);
            out.put("rules", ruleReport);
            Map<String, Object> res = new LinkedHashMap<>(); res.put("idcard", residualId); res.put("phone", residualPhone);
            out.put("residual", res);
            out.put("samples", samples);
            out.put("thresholds", thresholds == null ? new LinkedHashMap<>() : thresholds);
            out.put("metrics", buildMetrics(total, sumRaw, piiRaw, dupRaw, emptyRaw, shortRaw, histRaw, cleaned, sumClean, residualId + residualPhone, histClean));
            return out;
        }
    }

    private static void bump(Map<String, Integer> m, String k) { m.put(k, (m.get(k) == null ? 0 : m.get(k)) + 1); }

    /** 读 sys_config（缺失/异常回退默认） */
    private String cfg(String key, String def) {
        try { String v = configService.getByKey(key).getConfigValue(); return (v == null || v.isEmpty()) ? def : v; } catch (Exception e) { return def; }
    }

    private static int lenBucket(int n) { if (n < 20) return 0; if (n < 50) return 1; if (n < 100) return 2; if (n < 300) return 3; return 4; }
    private static long fnv(String tok) { long h = 0xcbf29ce484222325L; for (int i = 0; i < tok.length(); i++) { h ^= tok.charAt(i); h *= 0x100000001b3L; } return h; }
    private static long simhash(String s) {
        String t = s.replaceAll("\\s+", "");
        int[] v = new int[64]; int n = 0;
        for (int i = 0; i + 1 < t.length() && n < 4000; i++, n++) { long h = fnv(t.substring(i, i + 2)); for (int b = 0; b < 64; b++) v[b] += (((h >>> b) & 1L) == 1L) ? 1 : -1; }
        if (n == 0 && !t.isEmpty()) { long h = fnv(t); for (int b = 0; b < 64; b++) v[b] += (((h >>> b) & 1L) == 1L) ? 1 : -1; }
        long fp = 0L; for (int b = 0; b < 64; b++) if (v[b] > 0) fp |= (1L << b); return fp;
    }
    private static int hamming(long a, long b) { return Long.bitCount(a ^ b); }
    private static Map<String, Object> metricOne(int total, long sumLen, int pii, int dup, int empty, int shortN, int[] hist) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("total", total); o.put("avgLen", total > 0 ? Math.round((double) sumLen / total) : 0);
        o.put("pii", pii); o.put("dup", dup); o.put("empty", empty); o.put("shortN", shortN); o.put("hist", hist);
        return o;
    }
    private static Map<String, Object> buildMetrics(int tb, long sumB, int piiB, int dupB, int emptyB, int shortB, int[] hb, int ta, long sumA, int piiA, int[] ha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", metricOne(tb, sumB, piiB, dupB, emptyB, shortB, hb));
        m.put("after", metricOne(ta, sumA, piiA, 0, 0, 0, ha));
        return m;
    }

    private static String maskRegex(String s, Pattern p, int head, int tail) {
        java.util.regex.Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(maskKeep(m.group(1), head, tail))); }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskKeep(String s, int head, int tail) {
        if (s == null) return "";
        int len = s.length();
        StringBuilder sb = new StringBuilder();
        if (len <= head + tail) {
            for (int i = 0; i < len; i++) sb.append('*');
            return sb.toString();
        }
        sb.append(s, 0, head);
        for (int i = head; i < len - tail; i++) sb.append('*');
        sb.append(s.substring(len - tail));
        return sb.toString();
    }

    /* ===================== 条目 ===================== */

    public Map<String, Object> items(long datasetId, String status, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        String where = " WHERE dataset_id=?" + (status != null && !status.isEmpty() && !"all".equals(status) ? " AND clean_status=?" : "");
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ds_item" + where)) {
                ps.setLong(1, datasetId);
                if (where.contains("clean_status=?")) ps.setString(2, status);
                try (ResultSet rs = ps.executeQuery()) { out.put("total", rs.next() ? rs.getInt(1) : 0); }
            }
            int offset = Math.max(0, (page - 1) * size);
            String sql = "SELECT id,seq,clean_status,LEFT(COALESCE(clean_content,raw_content),400) AS preview FROM ds_item" + where + " ORDER BY seq LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1; ps.setLong(idx++, datasetId);
                if (where.contains("clean_status=?")) ps.setString(idx++, status);
                ps.setInt(idx++, size); ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) { out.put("records", mapRows(rs)); }
            }
        } catch (Exception e) { log.warn("dataset items: {}", e.getMessage()); out.put("records", new ArrayList<>()); out.put("total", 0); }
        return out;
    }

    /* ===================== 标注任务（LabelStudio 交换） ===================== */

    public long createAnnotTask(long datasetId, String name, String template, String instruction, String labelConfig) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            int total = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ds_item WHERE dataset_id=? AND clean_status<>'dropped'")) { ps.setLong(1, datasetId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getInt(1); } }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ds_annot_task(dataset_id,name,template,status,total,instruction,label_config) VALUES(?,?,?, 'created',?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, datasetId); ps.setString(2, name); ps.setString(3, template == null ? "text_classification" : template);
                ps.setInt(4, total); ps.setString(5, instruction); ps.setString(6, labelConfig);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0L; }
            }
        }
    }

    /** 导出 LabelStudio 可导入的 tasks JSON（[{"data":{"text":...}}]）。 */
    public String exportLsTasks(long datasetId, long taskId) throws Exception {
        ArrayNode arr = OM.createArrayNode();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id,COALESCE(clean_content,raw_content) AS t FROM ds_item WHERE dataset_id=? AND clean_status<>'dropped' ORDER BY seq")) {
            ps.setLong(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode o = OM.createObjectNode();
                    ObjectNode data = OM.createObjectNode();
                    data.put("text", rs.getString("t"));
                    o.set("data", data);
                    o.put("id", rs.getLong("id"));
                    arr.add(o);
                }
            }
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE ds_annot_task SET status='exported' WHERE id=?")) { ps.setLong(1, taskId); ps.executeUpdate(); }
        return OM.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
    }

    /** 导入 LabelStudio 导出结果（数组，元素含 data.text 或 id + annotations[].result）。 */
    public Map<String, Object> importLsResult(long datasetId, long taskId, MultipartFile file) throws Exception {
        return importLsArray(datasetId, taskId, OM.readTree(file.getInputStream()));
    }

    /** 导入结果数组（离线回传 / API 拉取共用）。 */
    private Map<String, Object> importLsArray(long datasetId, long taskId, JsonNode root) throws Exception {
        if (root == null || !root.isArray()) throw new IllegalArgumentException("LabelStudio 结果需为 JSON 数组");
        int imported = 0;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ds_annot_result(task_id,item_id,annotator,result) VALUES(?,?,?,?)")) {
                for (JsonNode t : root) {
                    Long itemId = t.path("data").hasNonNull("_item_id") ? t.path("data").get("_item_id").asLong() : (t.hasNonNull("id") ? t.get("id").asLong() : null);
                    String annotator = t.has("annotator") ? t.get("annotator").asText() : null;
                    String result = t.has("annotations") ? OM.writeValueAsString(t.get("annotations")) : OM.writeValueAsString(t);
                    ps.setLong(1, taskId);
                    if (itemId != null) ps.setLong(2, itemId); else ps.setNull(2, Types.BIGINT);
                    ps.setString(3, annotator != null ? annotator : "");
                    ps.setString(4, result);
                    ps.addBatch(); imported++;
                    if (imported % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE ds_annot_task SET status='imported',done=? WHERE id=?")) { ps.setInt(1, imported); ps.setLong(2, taskId); ps.executeUpdate(); }
            c.commit();
        }
        recomputeCounts(datasetId);
        Map<String, Object> out = new LinkedHashMap<>(); out.put("imported", imported);
        return out;
    }

    /* ---- Label Studio API 直连 ---- */

    /** 推送：中台建 LS 项目 + 导入样本，记录 external_id（项目号）。 */
    public Map<String, Object> pushToLs(long datasetId, long taskId) throws Exception {
        String base = cfg("labelstudio.base_url", ""), token = cfg("labelstudio.token", "");
        if (base.isEmpty() || token.isEmpty()) throw new IllegalStateException("未配置 Label Studio（labelstudio.base_url / token）");
        String title = "dataset" + datasetId + "-task" + taskId;
        // label_config 优先取标注任务自带（支持 NER/分类等）；为空再退回默认三分类
        String labelConfig = null;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT label_config FROM ds_annot_task WHERE id=?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) labelConfig = rs.getString(1); }
        }
        if (labelConfig == null || labelConfig.trim().isEmpty()) {
            labelConfig = "<View><Text name=\"text\" value=\"$text\"/><Choices name=\"label\" toName=\"text\"><Choice value=\"合格\"/><Choice value=\"优秀\"/><Choice value=\"不合格\"/></Choices></View>";
        }
        ObjectNode proj = OM.createObjectNode(); proj.put("title", title); proj.put("label_config", labelConfig);
        JsonNode pj = OM.readTree(http(base, "/api/projects", "POST", token, OM.writeValueAsString(proj)));
        long pid = pj.path("id").asLong();
        if (pid <= 0) throw new IllegalStateException("创建 Label Studio 项目失败：" + pj.toString());
        ArrayNode tasks = OM.createArrayNode();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id,COALESCE(clean_content,raw_content) AS t FROM ds_item WHERE dataset_id=? AND clean_status<>'dropped' ORDER BY seq")) {
            ps.setLong(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { ObjectNode o = OM.createObjectNode(); ObjectNode d = OM.createObjectNode(); d.put("text", rs.getString("t")); d.put("_item_id", rs.getLong("id")); o.set("data", d); tasks.add(o); } }
        }
        http(base, "/api/projects/" + pid + "/import", "POST", token, OM.writeValueAsString(tasks));
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE ds_annot_task SET external_id=?,status='pushed',total=? WHERE id=?")) {
            ps.setString(1, String.valueOf(pid)); ps.setInt(2, tasks.size()); ps.setLong(3, taskId); ps.executeUpdate();
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("projectId", pid); out.put("pushed", tasks.size());
        return out;
    }

    /** 拉取：从 LS 导出标注结果并回填。 */
    public Map<String, Object> pullFromLs(long datasetId, long taskId) throws Exception {
        String base = cfg("labelstudio.base_url", ""), token = cfg("labelstudio.token", "");
        if (base.isEmpty() || token.isEmpty()) throw new IllegalStateException("未配置 Label Studio（labelstudio.base_url / token）");
        String pid = null;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT external_id FROM ds_annot_task WHERE id=?")) {
            ps.setLong(1, taskId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) pid = rs.getString(1); }
        }
        if (pid == null || pid.isEmpty()) throw new IllegalStateException("该任务尚未推送到 Label Studio");
        String json = http(base, "/api/projects/" + pid + "/export?exportType=JSON", "GET", token, null);
        return importLsArray(datasetId, taskId, OM.readTree(json));
    }

    /** 连通性自检：用当前配置访问 Label Studio /api/projects。 */
    public Map<String, Object> lsPing() {
        Map<String, Object> out = new LinkedHashMap<>();
        String base = cfg("labelstudio.base_url", ""), token = cfg("labelstudio.token", "");
        if (base.isEmpty() || token.isEmpty()) { out.put("ok", false); out.put("error", "未配置 labelstudio.base_url / token"); return out; }
        try {
            String json = http(base, "/api/projects", "GET", token, null);
            JsonNode j = OM.readTree(json);
            out.put("ok", true);
            out.put("projects", j.has("count") ? j.get("count").asInt() : j.path("results").size());
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    /** 调用 Label Studio 接口（Java 8 HttpURLConnection）。 */
    private static String http(String base, String path, String method, String token, String body) throws Exception {
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(base + path).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(8000); con.setReadTimeout(60000);
        con.setRequestProperty("Authorization", "Token " + token);
        if (body != null) { con.setDoOutput(true); con.setRequestProperty("Content-Type", "application/json"); try (java.io.OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code = con.getResponseCode();
        java.io.InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) { try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) { String l; while ((l = br.readLine()) != null) sb.append(l); } }
        if (code >= 400) throw new IllegalStateException("Label Studio 返回 " + code + "：" + (sb.length() > 200 ? sb.substring(0, 200) : sb.toString()));
        return sb.toString();
    }

    /* ===================== 数据集版本 ===================== */

    public Map<String, Object> createVersion(long datasetId, String name, int trainRatio, String lineage) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            int ver = 1, total = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version),0) FROM ds_version WHERE dataset_id=?")) { ps.setLong(1, datasetId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) ver = rs.getInt(1) + 1; } }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ds_item WHERE dataset_id=? AND clean_status<>'dropped'")) { ps.setLong(1, datasetId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getInt(1); } }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ds_version(dataset_id,version,name,train_ratio,item_count,lineage,format) VALUES(?,?,?,?,?,?, 'alpaca')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, datasetId); ps.setInt(2, ver); ps.setString(3, name == null ? "v" + ver : name);
                ps.setInt(4, trainRatio); ps.setInt(5, total); ps.setString(6, lineage);
                ps.executeUpdate();
                long id = 0; try (ResultSet k = ps.getGeneratedKeys()) { if (k.next()) id = k.getLong(1); }
                Map<String, Object> out = new LinkedHashMap<>(); out.put("id", id); out.put("version", ver); out.put("item_count", total);
                return out;
            }
        }
    }

    /** 把 LS 标注结果转成训练可用的 output：
     *  实体(labels) → {"entities":[{start,end,text,label}], "bio":"字\t标签…"}（字符级 BIO，适合中文）；
     *  分类(choices) → {"labels":[...]}；无法识别则原样返回。 */
    private static String annToTrain(String text, String annJson) {
        if (annJson == null || annJson.trim().isEmpty()) return "";
        try {
            JsonNode root = OM.readTree(annJson);
            JsonNode results = null;
            if (root.isArray() && root.size() > 0 && root.get(0).has("result")) results = root.get(0).get("result");
            else if (root.isObject() && root.has("result")) results = root.get("result");
            if (results == null || !results.isArray()) return annJson;
            ArrayNode ents = OM.createArrayNode();
            ArrayNode choices = OM.createArrayNode();
            for (JsonNode it : results) {
                String type = it.path("type").asText("");
                JsonNode v = it.path("value");
                if ("labels".equals(type)) {
                    ObjectNode e = OM.createObjectNode();
                    e.put("start", v.path("start").asInt());
                    e.put("end", v.path("end").asInt());
                    e.put("text", v.path("text").asText(""));
                    e.put("label", v.path("labels").isArray() && v.get("labels").size() > 0 ? v.get("labels").get(0).asText() : "");
                    ents.add(e);
                } else if ("choices".equals(type) && v.path("choices").isArray()) {
                    for (JsonNode cn : v.get("choices")) choices.add(cn.asText());
                }
            }
            ObjectNode out = OM.createObjectNode();
            if (ents.size() > 0) {
                out.set("entities", ents);
                StringBuilder bio = new StringBuilder();
                String s = text == null ? "" : text;
                for (int i = 0; i < s.length(); i++) {
                    String tag = "O";
                    for (JsonNode e : ents) {
                        int st = e.get("start").asInt(), en = e.get("end").asInt();
                        if (i >= st && i < en) { tag = (i == st ? "B-" : "I-") + e.get("label").asText(); break; }
                    }
                    if (i > 0) bio.append('\n');
                    bio.append(s.charAt(i)).append('\t').append(tag);
                }
                out.put("bio", bio.toString());
            } else if (choices.size() > 0) {
                out.set("labels", choices);
            } else {
                return annJson;
            }
            return OM.writeValueAsString(out);
        } catch (Exception e) { return annJson; }
    }

    /** 导出：alpaca / sharegpt / jsonl。条目原文若为 JSON 且含 instruction/output 则直接取字段。 */
    public String exportVersion(long datasetId, String format) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean sharegpt = "sharegpt".equalsIgnoreCase(format);
        boolean jsonl = "jsonl".equalsIgnoreCase(format);
        ArrayNode arr = sharegpt ? OM.createArrayNode() : null;
        // 标注结果并入导出：item_id -> 最新标注 result（同一 item 多次标注取最新）
        java.util.Map<Long, String> ann = new java.util.HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id,COALESCE(clean_content,raw_content) AS t FROM ds_item WHERE dataset_id=? AND clean_status<>'dropped' ORDER BY seq")) {
            try (PreparedStatement pa = c.prepareStatement(
                    "SELECT r.item_id, r.result FROM ds_annot_result r JOIN ds_annot_task t ON r.task_id=t.id WHERE t.dataset_id=? AND r.item_id IS NOT NULL ORDER BY r.id")) {
                pa.setLong(1, datasetId);
                try (ResultSet ra = pa.executeQuery()) { while (ra.next()) ann.put(ra.getLong(1), ra.getString(2)); }
            }
            ps.setLong(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long itemId = rs.getLong("id");
                    String t = rs.getString("t");
                    String ins = t, out = "";
                    try {
                        JsonNode j = OM.readTree(t);
                        if (j.isObject() && j.has("instruction")) {
                            ins = j.path("instruction").asText("");
                            String input = j.path("input").asText("");
                            out = j.path("output").asText(j.path("response").asText(""));
                            if (!input.isEmpty()) ins = ins + "\n" + input;
                        }
                    } catch (Exception ignore) { }
                    // 若该条已有标注 → 转成训练格式作为 output（实体→entities/BIO，分类→labels）
                    String annOut = ann.get(itemId);
                    if (annOut != null && !annOut.isEmpty()) out = annToTrain(t, annOut);
                    if (sharegpt) {
                        ObjectNode o = OM.createObjectNode();
                        ArrayNode conv = OM.createArrayNode();
                        ObjectNode h = OM.createObjectNode(); h.put("from", "human"); h.put("value", ins); conv.add(h);
                        ObjectNode g = OM.createObjectNode(); g.put("from", "gpt"); g.put("value", out); conv.add(g);
                        o.set("conversations", conv);
                        arr.add(o);
                    } else if (jsonl) {
                        ObjectNode o = OM.createObjectNode(); o.put("instruction", ins); o.put("input", ""); o.put("output", out);
                        sb.append(OM.writeValueAsString(o)).append('\n');
                    } else { // alpaca 数组
                        if (arr == null) arr = OM.createArrayNode();
                        ObjectNode o = OM.createObjectNode(); o.put("instruction", ins); o.put("input", ""); o.put("output", out);
                        arr.add(o);
                    }
                }
            }
        }
        if (sharegpt || format == null || "alpaca".equalsIgnoreCase(format)) {
            if (arr == null) arr = OM.createArrayNode();
            return OM.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        }
        return sb.toString();
    }

    /* ===================== 工具 ===================== */

    private void recomputeCounts(long datasetId) {
        try (Connection c = dataSource.getConnection()) {
            int total = 0, cleaned = 0, annotated = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) total, SUM(clean_status='cleaned') cleaned FROM ds_item WHERE dataset_id=?")) {
                ps.setLong(1, datasetId);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { total = rs.getInt(1); cleaned = rs.getInt(2); } }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(done),0) FROM ds_annot_task WHERE dataset_id=?")) { ps.setLong(1, datasetId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) annotated = rs.getInt(1); } }
            try (PreparedStatement ps = c.prepareStatement("UPDATE ds_dataset SET item_count=?,cleaned_count=?,annotated_count=? WHERE id=?")) {
                ps.setInt(1, total); ps.setInt(2, cleaned); ps.setInt(3, annotated); ps.setLong(4, datasetId); ps.executeUpdate();
            }
        } catch (Exception e) { log.warn("recomputeCounts: {}", e.getMessage()); }
    }

    private List<Map<String, Object>> queryList(Connection c, String sql, long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { out = mapRows(rs); } }
        catch (Exception ignore) { }
        return out;
    }

    private static List<Map<String, Object>> mapRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                Object v = rs.getObject(i);
                if (v instanceof Timestamp) v = v.toString().replace('T', ' ');
                row.put(md.getColumnLabel(i), v);
            }
            out.add(row);
        }
        return out;
    }

    private static String md5(String s) {
        try { MessageDigest m = MessageDigest.getInstance("MD5"); byte[] d = m.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (Exception e) { return Integer.toHexString((s == null ? "" : s).hashCode()); }
    }
}
