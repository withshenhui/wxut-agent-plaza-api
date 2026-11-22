package com.wxut.agentplaza.service.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.KbChunk;
import com.wxut.agentplaza.entity.KbFile;
import com.wxut.agentplaza.mapper.KbChunkMapper;
import com.wxut.agentplaza.mapper.KbFileMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import com.wxut.agentplaza.service.form.FormLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Fully automatic processing pipeline per file:
 * clean -> chunk -> qa(LLM, best-effort) -> embed -> store.
 * Serial single worker: GPU4 embedding is shared, keep batches small and ordered.
 */
@Service
public class KbPipelineService {

    private static final Logger log = LoggerFactory.getLogger(KbPipelineService.class);

    private final KbFileMapper fileMapper;
    private final KbChunkMapper chunkMapper;
    private final KbMapper kbMapper;
    private final KbTextExtractor extractor;
    private final KbChunker chunker;
    private final KbEmbeddingService embedding;
    private final FormLlmService llm;
    private final ObjectMapper om = new ObjectMapper();

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<Long>();
    private volatile boolean started = false;

    public KbPipelineService(KbFileMapper fileMapper, KbChunkMapper chunkMapper, KbMapper kbMapper,
                             KbTextExtractor extractor, KbChunker chunker,
                             KbEmbeddingService embedding, FormLlmService llm) {
        this.fileMapper = fileMapper; this.chunkMapper = chunkMapper;
        this.kbMapper = kbMapper; this.extractor = extractor;
        this.chunker = chunker; this.embedding = embedding; this.llm = llm;
    }

    public void enqueue(Long fileId) {
        ensureStarted();
        queue.offer(fileId);
    }

    private synchronized void ensureStarted() {
        if (started) return;
        started = true;
        Thread worker = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "kb-pipeline");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            try {
                Long fileId = queue.take();
                process(fileId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("kb pipeline fatal for one file", e);
            }
        }
    }

    void process(Long fileId) {
        KbFile f = fileMapper.selectById(fileId);
        if (f == null || "done".equals(f.getStatus())) return;
        try {
            f.setStatus("processing");
            update(f);

            stage(f, "clean");
            String raw = extractor.extract(java.nio.file.Paths.get(f.getPath()), f.getExt());
            String cleaned = clean(raw);

            stage(f, "chunk");
            List<KbChunker.Chunk> pieces = chunker.split(cleaned);
            if (pieces.isEmpty()) throw new IllegalStateException("no text extracted");

            // delete previous chunks of this file (rebuild case)
            KbChunk del = new KbChunk();
            del.setFileId(fileId);
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KbChunk>()
                    .eq("file_id", fileId));

            // qa generation (best-effort, per chunk 2-3 pairs)
            stage(f, "qa");
            List<String> qaContents = new ArrayList<String>();
            List<String> qaQuestions = new ArrayList<String>();
            List<String> qaDocs = new ArrayList<String>();
            for (KbChunker.Chunk c : pieces) {
                List<String[]> pairs = generateQa(f.getName(), c);
                for (String[] p : pairs) {
                    qaQuestions.add(p[0]);
                    qaContents.add(p[0] + "\n" + p[1]);
                    qaDocs.add(clauseOr(c));
                }
            }

            stage(f, "embed");
            List<String> toEmbed = new ArrayList<String>();
            for (KbChunker.Chunk c : pieces) toEmbed.add(c.text);
            toEmbed.addAll(qaContents);
            List<float[]> vectors = embedding.embed(toEmbed);

            stage(f, "store");
            int seq = 0;
            int stored = 0;
            for (int i = 0; i < pieces.size(); i++) {
                KbChunker.Chunk c = pieces.get(i);
                chunkMapper.insert(chunk(f, "raw", seq++, c.text, null, c.clauseNo, vectors.get(i)));
                stored++;
            }
            for (int i = 0; i < qaContents.size(); i++) {
                chunkMapper.insert(chunk(f, "qa", seq++, qaContents.get(i), qaQuestions.get(i),
                        qaDocs.get(i), vectors.get(pieces.size() + i)));
                stored++;
            }

            f.setStatus("done");
            f.setStage(null);
            f.setChunkCount(pieces.size());
            f.setQaCount(qaContents.size());
            f.setErrorMsg(null);
            update(f);
            bumpKb(f.getKbId(), pieces.size(), qaContents.size());
            VectorCache.invalidate(f.getKbId());
            log.info("kb file done: {} chunks={} qa={}", f.getName(), pieces.size(), qaContents.size());
        } catch (Exception e) {
            f.setStatus("failed");
            f.setErrorMsg(e.getMessage() == null ? e.getClass().getSimpleName()
                    : (e.getMessage().length() > 480 ? e.getMessage().substring(0, 480) : e.getMessage()));
            update(f);
            log.error("kb file failed: {}", f.getName(), e);
        }
    }

    private String clauseOr(KbChunker.Chunk c) {
        return c.clauseNo == null || c.clauseNo.isEmpty() ? "" : c.clauseNo;
    }

    private KbChunk chunk(KbFile f, String type, int seq, String content, String question, String clause, float[] v) {
        KbChunk c = new KbChunk();
        c.setKbId(f.getKbId());
        c.setFileId(f.getId());
        c.setChunkType(type);
        c.setSeq(seq);
        c.setContent(content);
        c.setQaQuestion(question);
        c.setDocName(f.getName());
        c.setClauseNo(clause);
        c.setEmbedding(KbEmbeddingService.toBlob(v));
        return c;
    }

    private List<String[]> generateQa(String docName, KbChunker.Chunk c) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            String system = "你是制度文档问答对生成器。对给出的制度条款片段生成2-3条校园场景问答对。"
                    + "问题用老师或学生自然会问的口语；答案严格来自原文不得编造并保留条款号；"
                    + "每条一行 JSON：{\"q\":\"...\",\"a\":\"...\"}，不要输出其他内容。";
            String user = "文档：" + docName + "\n条款片段：" + c.text;
            String resp = llm.chat(system, user);
            for (String line : resp.split("\n")) {
                line = line.trim();
                if (line.startsWith("{")) {
                    try {
                        JsonNode n = om.readTree(line);
                        String q = n.path("q").asText(""), a = n.path("a").asText("");
                        if (!q.isEmpty() && !a.isEmpty()) out.add(new String[]{q, a});
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            log.warn("qa generation skipped for {} ({}): {}", docName, c.clauseNo, e.getMessage());
        }
        return out;
    }

    /** Remove repeated blank lines, BOM, page-number-only lines. */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\n")) {
            String t = line.replace('\u00A0', ' ').replace('\uFEFF', ' ').trim();
            if (t.matches("\\d{1,3}|第\\s*\\d+\\s*页.*|[-—_]{3,}")) continue;
            if (t.isEmpty() && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') continue;
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    private void stage(KbFile f, String s) {
        f.setStage(s);
        update(f);
    }

    private void update(KbFile f) {
        fileMapper.updateById(f);
    }

    private void bumpKb(Long kbId, int chunks, int qas) {
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null) return;
        kb.setChunkCount((kb.getChunkCount() == null ? 0 : kb.getChunkCount()) + chunks);
        kb.setQaCount((kb.getQaCount() == null ? 0 : kb.getQaCount()) + qas);
        kbMapper.updateById(kb);
    }
}
