package com.wxut.agentplaza.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.KbChunk;
import com.wxut.agentplaza.mapper.KbChunkMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hybrid retrieval: cosine over cached vectors, optional rerank later. */
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

    private final KbMapper kbMapper;
    private final KbChunkMapper chunkMapper;
    private final KbEmbeddingService embedding;

    public KbSearchService(KbMapper kbMapper, KbChunkMapper chunkMapper, KbEmbeddingService embedding) {
        this.kbMapper = kbMapper; this.chunkMapper = chunkMapper; this.embedding = embedding;
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
        return all.size() > topK ? new ArrayList<Hit>(all.subList(0, topK)) : all;
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
