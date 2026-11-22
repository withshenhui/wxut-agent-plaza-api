package com.wxut.agentplaza.service.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.service.SysConfigService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding client for the campus vLLM service (245:8014, qwen3-embedding-4b, 2560 dims).
 * Endpoint/base key come from sys_config so they can be switched without redeploy:
 *   kb.embed_base_url (default http://210.28.144.245:8014/v1)
 *   kb.embed_model    (default qwen3-embedding-4b)
 *   kb.embed_api_key  (default campus key)
 */
@Service
public class KbEmbeddingService {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int BATCH_LIMIT = 16;

    private final SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    public KbEmbeddingService(SysConfigService configService) {
        this.configService = configService;
    }

    public static class Cfg {
        public final String baseUrl, apiKey, model;
        Cfg(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl; this.apiKey = apiKey; this.model = model;
        }
    }

    public Cfg cfg() {
        return new Cfg(
                cfgVal("kb.embed_base_url", "http://210.28.144.245:8014/v1"),
                cfgVal("kb.embed_api_key", "qemb-K9vR2tX7yZ4aB6cD8eF1gH3jK5lM7nP9qS"),
                cfgVal("kb.embed_model", "qwen3-embedding-4b"));
    }

    private String cfgVal(String key, String dft) {
        try {
            String v = configService.getByKey(key).getConfigValue();
            if (v != null && !v.trim().isEmpty()) return v.trim();
        } catch (Exception ignore) {}
        return dft;
    }

    /**
     * Embed texts (batched internally, serial — GPU4 is shared).
     * @return float32 vectors in input order, each 2560 dims.
     */
    public List<float[]> embed(List<String> texts) throws Exception {
        List<float[]> out = new ArrayList<float[]>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_LIMIT) {
            List<String> part = texts.subList(i, Math.min(i + BATCH_LIMIT, texts.size()));
            out.addAll(embedBatch(part));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) throws Exception {
        Cfg c = cfg();
        StringBuilder body = new StringBuilder("{\"model\":\"").append(c.model).append("\",\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) body.append(',');
            body.append(om.writeValueAsString(texts.get(i)));
        }
        body.append("]}");

        HttpURLConnection conn = (HttpURLConnection) new URL(c.baseUrl + "/embeddings").openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + c.apiKey);
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();

        int status = conn.getResponseCode();
        String resp = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (status != 200) throw new IllegalStateException("embed HTTP " + status + ": " + trunc(resp));

        JsonNode data = om.readTree(resp).path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("embed response size mismatch");
        }
        List<float[]> vectors = new ArrayList<float[]>(texts.size());
        for (JsonNode item : data) {
            JsonNode arr = item.path("embedding");
            float[] v = new float[arr.size()];
            for (int j = 0; j < arr.size(); j++) v[j] = (float) arr.get(j).asDouble();
            vectors.add(v);
        }
        return vectors;
    }

    /** float32 little-endian BLOB for MySQL. */
    public static byte[] toBlob(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    public static float[] fromBlob(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
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
}
