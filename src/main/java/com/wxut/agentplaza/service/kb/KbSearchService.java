package com.wxut.agentplaza.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.KbChunk;
import com.wxut.agentplaza.mapper.KbChunkMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import com.wxut.agentplaza.service.SysConfigService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval: cosine over cached vectors, then campus rerank (245:8015 bge-reranker, best-effort).
 * Rerank endpoint/model come from sys_config so they can be switched without redeploy:
 *   kb.rerank_base_url (default http://210.28.144.245:8015/v1)
 *   kb.rerank_model    (default bge-reranker-v2-m3)
 */
@Service
public class KbSearchService {

    public static class Hit {
        public String type;      // qa | raw
        public float score;
        public String doc;
        public String loc;
        public String q;         // qa only
        public String a;         // qa only
        public String txt;       // raw only
    }

    private static final int RERANK_CANDIDATES = 20;

    private final KbMapper kbMapper;
    private final KbChunkMapper chunkMapper;
    private final KbEmbeddingService embedding;
    private final SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    public KbSearchService(KbMapper kbMapper, KbChunkMapper chunkMapper, KbEmbeddingService embedding,
                           SysConfigService configService) {
        this.kbMapper = kbMapper; this.chunkMapper = chunkMapper;
        this.embedding = embedding; this.configService = configService;
    }

    public List<Hit> search(List<Long> kbIds, String query, int topK) throws Exception {
        List<Hit> all = new ArrayList<Hit>();
        float[] qv = embedding.embed(java.util.Collections.singletonList(query)).get(0);
        for (Long kbId : kbIds) {
            List<VectorCache.Entry> entries = VectorCache.get(kbId, new VectorCache.Loader() {
                public List<VectorCache.Entry> load() { return loadVectors(kbId); }
            });
            if (entries.isEmpty()) continue;
            // chunk meta by id
            Map<Long, KbChunk> meta = new HashMap<Long, KbChunk>();
            for (KbChunk c : chunkMapper.selectList(new QueryWrapper<KbChunk>().eq("kb_id", kbId))) {
                meta.put(c.getId(), c);
            }
            List<Hit> kbHits = new ArrayList<Hit>();
            for (VectorCache.Entry e : entries) {
                float s = VectorCache.cos(qv, e.vector);
                if (s < 0.25f) continue;
                Hit h = toHit(meta.get(e.chunkId), s);
                if (h != null) kbHits.add(h);
            }
            all.addAll(kbHits);
        }
        all.sort(new Comparator<Hit>() {
            public int compare(Hit x, Hit y) { return Float.compare(y.score, x.score); }
        });
        all = rerank(query, all);
        return all.size() > topK ? new ArrayList<Hit>(all.subList(0, topK)) : all;
    }

    /** Cross-encoder rerank of the top candidates via the campus rerank service; falls back to cosine order. */
    private List<Hit> rerank(String query, List<Hit> sorted) {
        int n = Math.min(sorted.size(), RERANK_CANDIDATES);
        if (n <= 1) return sorted;
        List<Hit> candidates = new ArrayList<Hit>(sorted.subList(0, n));
        try {
            String base = cfgVal("kb.rerank_base_url", "http://210.28.144.245:8015/v1");
            String model = cfgVal("kb.rerank_model", "bge-reranker-v2-m3");
            String apiKey = cfgVal("kb.rerank_api_key", "rrkv2-L4mN8pQ2rT6vX3yY1zA5cE7gJ0kM5nP8s");
            StringBuilder body = new StringBuilder("{\"model\":").append(om.writeValueAsString(model))
                    .append(",\"query\":").append(om.writeValueAsString(query)).append(",\"documents\":[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) body.append(',');
                body.append(om.writeValueAsString(hitText(candidates.get(i))));
            }
            body.append("]}");

            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/rerank").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int status = conn.getResponseCode();
            String resp = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            conn.disconnect();
            if (status != 200) throw new IllegalStateException("rerank HTTP " + status + ": " + trunc(resp));

            final Map<Integer, Float> scores = new HashMap<Integer, Float>();
            JsonNode results = om.readTree(resp).path("results");
            if (!results.isArray() || results.size() == 0) throw new IllegalStateException("rerank empty results");
            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                scores.put(r.path("index").asInt(-1), (float) r.path("relevance_score").asDouble());
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (!scores.containsKey(i)) scores.put(i, 0f);
            }
            List<Hit> reordered = new ArrayList<Hit>(candidates);
            final Map<Hit, Integer> origPos = new HashMap<Hit, Integer>();
            for (int i = 0; i < candidates.size(); i++) origPos.put(candidates.get(i), i);
            java.util.Collections.sort(reordered, new Comparator<Hit>() {
                public int compare(Hit x, Hit y) {
                    int c = Float.compare(scores.get(origPos.get(y)), scores.get(origPos.get(x)));
                    return c != 0 ? c : Integer.compare(origPos.get(x), origPos.get(y));
                }
            });
            for (int i = 0; i < reordered.size(); i++) {
                Float s = scores.get(origPos.get(reordered.get(i)));
                if (s != null) reordered.get(i).score = s;
            }
            List<Hit> out = new ArrayList<Hit>(reordered);
            out.addAll(sorted.subList(n, sorted.size()));
            return out;
        } catch (Exception e) {
            System.err.println("[kb-rerank] failed, keep cosine order: " + e.getMessage());
            return sorted;
        }
    }

    private String hitText(Hit h) {
        if ("qa".equals(h.type)) return (h.q == null ? "" : h.q) + "\n" + (h.a == null ? "" : h.a);
        return h.txt == null ? "" : h.txt;
    }

    private String cfgVal(String key, String dft) {
        try {
            String v = configService.getByKey(key).getConfigValue();
            if (v != null && !v.trim().isEmpty()) return v.trim();
        } catch (Exception ignore) {}
        return dft;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trunc(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    private Hit toHit(KbChunk c, float score) {
        if (c == null) return null;
        Hit h = new Hit();
        h.type = c.getChunkType();
        h.score = score;
        h.doc = c.getDocName();
        h.loc = c.getClauseNo() == null ? "" : c.getClauseNo();
        if ("qa".equals(c.getChunkType())) {
            h.q = c.getQaQuestion();
            int nl = c.getContent() == null ? -1 : c.getContent().indexOf('\n');
            h.a = nl > 0 ? c.getContent().substring(nl + 1) : c.getContent();
        } else {
            h.txt = c.getContent();
        }
        return h;
    }

    private List<VectorCache.Entry> loadVectors(Long kbId) {
        List<VectorCache.Entry> out = new ArrayList<VectorCache.Entry>();
        List<KbChunk> chunks = chunkMapper.selectList(new QueryWrapper<KbChunk>().eq("kb_id", kbId));
        for (KbChunk c : chunks) {
            if (c.getEmbedding() == null) continue;
            out.add(new VectorCache.Entry(c.getId(), KbEmbeddingService.fromBlob(c.getEmbedding())));
        }
        return out;
    }


    /** Wire format: {hits:[{type,score,doc,loc,q,a,txt}], summary:{total,qa,raw}} */
    public Map<String, Object> searchResult(List<Long> kbIds, String query, int topK) throws Exception {
        List<Hit> hits = search(kbIds, query, topK);
        Map<String, Object> out = new HashMap<String, Object>();
        List<Map<String, Object>> hs = new ArrayList<Map<String, Object>>();
        int nQa = 0;
        for (Hit h : hits) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", h.type);
            m.put("score", Math.round(h.score * 100) / 100.0);
            m.put("doc", h.doc);
            m.put("loc", h.loc);
            if ("qa".equals(h.type)) { m.put("q", h.q); m.put("a", h.a); nQa++; }
            else m.put("txt", h.txt);
            hs.add(m);
        }
        out.put("hits", hs);
        Map<String, Object> sum = new HashMap<String, Object>();
        sum.put("total", hits.size());
        sum.put("qa", nQa);
        sum.put("raw", hits.size() - nQa);
        out.put("summary", sum);
        return out;
    }

    public List<Long> visibleKbIds(Long userId, Integer deptId, String scope) {
        QueryWrapper<Kb> w = new QueryWrapper<Kb>();
        if ("personal".equals(scope)) {
            w.eq("owner_user_id", userId).eq("visibility", "private");
        } else if ("kb".equals(scope)) {
            // caller-supplied kbIds path; dept auto-attach handled by controller
        }
        List<Kb> kbs = kbMapper.selectList(w);
        List<Long> ids = new ArrayList<Long>();
        for (Kb kb : kbs) ids.add(kb.getId());
        return ids;
    }
}
