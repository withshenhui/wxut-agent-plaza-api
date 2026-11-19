package com.wxut.agentplaza.service.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wxut.agentplaza.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 智能填报-大模型访问（OpenAI 兼容协议）。
 * 配置来源 sys_config：llm.base_url / llm.api_key / llm.model（与查询网关共用一套配置，
 * 可在管理后台"大模型配置"在线切换并即时生效）。
 *
 * <p>HTTP 用 JDK8 原生 {@link HttpURLConnection} 实现（不依赖 java.net.http，保证线上 JDK8 可运行）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormLlmService {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int CHAT_READ_TIMEOUT_MS = 60000;
    private static final int STREAM_READ_TIMEOUT_MS = 180000;

    private final SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    public static class Cfg {
        public final String baseUrl, apiKey, model;
        Cfg(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl; this.apiKey = apiKey; this.model = model;
        }
        public boolean ready() {
            return baseUrl != null && !baseUrl.isEmpty() && model != null && !model.isEmpty();
        }
    }

    public Cfg cfg() {
        String base = "", key = "", model = "";
        try { base = nz(configService.getByKey("llm.base_url").getConfigValue()); } catch (Exception ignore) {}
        try { key = nz(configService.getByKey("llm.api_key").getConfigValue()); } catch (Exception ignore) {}
        try { model = nz(configService.getByKey("llm.model").getConfigValue()); } catch (Exception ignore) {}
        return new Cfg(base.replaceAll("/+$", ""), key, model);
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    /** 非流式对话：返回完整文本。失败抛异常，由调用方兜底。 */
    public String chat(String systemPrompt, String userPrompt) throws Exception {
        Cfg c = cfg();
        if (!c.ready()) throw new IllegalStateException("大模型未配置（sys_config llm.*）");
        ObjectNode body = buildBody(c.model, false, systemPrompt, userPrompt);

        HttpURLConnection conn = open(c, c.baseUrl + "/chat/completions", CHAT_READ_TIMEOUT_MS);
        try {
            write(conn, om.writeValueAsString(body));
            int status = conn.getResponseCode();
            String respText = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status != 200) throw new IllegalStateException("LLM HTTP " + status + ": " + trunc(respText));
            JsonNode root = om.readTree(respText);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) throw new IllegalStateException("LLM 返回缺少 content");
            return content.asText("");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 流式对话：每收到一个增量片段回调 onDelta。返回拼接的完整文本。
     * 失败抛异常，由调用方兜底。
     */
    public String chatStream(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onDelta) throws Exception {
        Cfg c = cfg();
        if (!c.ready()) throw new IllegalStateException("大模型未配置（sys_config llm.*）");
        ObjectNode body = buildBody(c.model, true, systemPrompt, userPrompt);

        HttpURLConnection conn = open(c, c.baseUrl + "/chat/completions", STREAM_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "text/event-stream");
        try {
            write(conn, om.writeValueAsString(body));
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException("LLM HTTP " + status + ": " + trunc(readAll(conn.getErrorStream())));
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        JsonNode delta = om.readTree(data).path("choices").path(0).path("delta").path("content");
                        if (delta.isMissingNode()) continue;
                        String piece = delta.asText("");
                        if (!piece.isEmpty()) {
                            full.append(piece);
                            onDelta.accept(piece);
                        }
                    } catch (Exception ignore) {
                        // 单个分片解析失败不影响后续流
                    }
                }
            }
            if (full.length() == 0) throw new IllegalStateException("LLM 流式返回为空");
            return full.toString();
        } finally {
            conn.disconnect();
        }
    }

    private ObjectNode buildBody(String model, boolean stream, String systemPrompt, String userPrompt) {
        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        ArrayNode msgs = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode m = msgs.addObject(); m.put("role", "system"); m.put("content", systemPrompt);
        }
        ObjectNode u = msgs.addObject(); u.put("role", "user"); u.put("content", userPrompt);
        return body;
    }

    private static HttpURLConnection open(Cfg c, String url, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        // SSE 场景必须禁用压缩/缓冲，否则分片会被攒到最后一次性到达
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (c.apiKey != null && !c.apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + c.apiKey);
        }
        return conn;
    }

    private static void write(HttpURLConnection conn, String json) throws Exception {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
