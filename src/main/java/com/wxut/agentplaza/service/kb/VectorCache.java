package com.wxut.agentplaza.service.kb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory vector cache per kb. Lazy load on miss; invalidate on file change.
 * Not a Spring bean on purpose: static lifecycle shared by pipeline + search.
 */
public final class VectorCache {

    public static class Entry {
        public final long chunkId;
        public final float[] vector;
        public Entry(long chunkId, float[] vector) { this.chunkId = chunkId; this.vector = vector; }
    }

    private static final int MAX_KB = 32;
    private static final Map<Long, List<Entry>> CACHE = new HashMap<Long, List<Entry>>();

    private VectorCache() {}

    public static List<Entry> get(Long kbId, Loader loader) {
        synchronized (CACHE) {
            List<Entry> hit = CACHE.get(kbId);
            if (hit != null) return hit;
            List<Entry> loaded = loader.load();
            if (CACHE.size() >= MAX_KB) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
            CACHE.put(kbId, loaded);
            return loaded;
        }
    }

    public static void invalidate(Long kbId) {
        synchronized (CACHE) {
            CACHE.remove(kbId);
        }
    }

    public interface Loader {
        List<Entry> load();
    }

    /** cosine similarity */
    public static float cos(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }
}
