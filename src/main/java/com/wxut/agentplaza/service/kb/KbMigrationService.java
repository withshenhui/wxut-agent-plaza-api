package com.wxut.agentplaza.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.KbChunk;
import com.wxut.agentplaza.entity.KbFile;
import com.wxut.agentplaza.mapper.KbChunkMapper;
import com.wxut.agentplaza.mapper.KbFileMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot migration of the legacy policy corpus into the public campus KB.
 * Sources under /app/aicenter-kb/migrate/:
 *   zhidu_index.json  (203 docs / 8436 QA pairs)
 * Triggered only when sys_config kb.migrate.enabled=true; auto-clears the flag after success.
 */
@Service
public class KbMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbMigrationService.class);
    private static final Path MIGRATE_DIR = Paths.get("/app/aicenter-kb/migrate");
    private static final String KB_NAME = "学校制度库";

    private final KbMapper kbMapper;
    private final KbFileMapper fileMapper;
    private final KbChunkMapper chunkMapper;
    private final KbTextExtractor extractor;
    private final KbChunker chunker;
    private final KbEmbeddingService embedding;
    private final com.wxut.agentplaza.service.SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    public KbMigrationService(KbMapper kbMapper, KbFileMapper fileMapper, KbChunkMapper chunkMapper,
                              KbTextExtractor extractor, KbChunker chunker, KbEmbeddingService embedding,
                              com.wxut.agentplaza.service.SysConfigService configService) {
        this.kbMapper = kbMapper; this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper; this.extractor = extractor;
        this.chunker = chunker; this.embedding = embedding;
        this.configService = configService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean enabled = false;
        try {
            String v = configService.getByKey("kb.migrate.enabled").getConfigValue();
            enabled = "true".equals(v == null ? "" : v.trim());
        } catch (Exception ignore) {}
        if (!enabled) return;
        try {
            migrate();
            setFlag("false");
            log.info("kb migration finished OK");
        } catch (Exception e) {
            log.error("kb migration failed (flag kept for retry)", e);
        }
    }

    void migrate() throws Exception {
        Path idx = MIGRATE_DIR.resolve("zhidu_index.json");
        if (!Files.exists(idx)) throw new IllegalStateException("migrate dir missing zhidu_index.json");
        JsonNode root = om.readTree(Files.readAllBytes(idx));
        JsonNode docs = root.path("docs");
        if (!docs.isArray() || docs.size() == 0) throw new IllegalStateException("index has no docs");

        // find-or-create the public campus KB
        Kb kb = kbMapper.selectOne(new QueryWrapper<Kb>().eq("name", KB_NAME)
                .eq("visibility", "public").last("LIMIT 1"));
        if (kb == null) {
            kb = new Kb();
            kb.setName(KB_NAME);
            kb.setDescription("学校规章制度库（203 篇 / 问答对 + 原文块双粒度），由存量制度资产迁移。");
            kb.setOwnerUserId(1L);
            kb.setVisibility("public");
            kb.setStatus("ok");
            kb.setFileCount(0); kb.setChunkCount(0); kb.setQaCount(0); kb.setSizeBytes(0L);
            kbMapper.insert(kb);
        }
        final Long kbId = kb.getId();

        int fileTotal = 0, chunkTotal = 0, qaTotal = 0, qaDropped = 0;

        for (JsonNode doc : docs) {
            String name = doc.path("name").asText("");
            String category = doc.path("category").asText("");
            if (name.isEmpty()) continue;

            // skip if this doc already migrated (idempotent re-run)
            Long existing = fileMapper.selectCount(new QueryWrapper<KbFile>()
                    .eq("kb_id", kbId).eq("name", name));
            if (existing != null && existing > 0) continue;

            // legacy corpus is QA-only (zhidu_index.json built from Q:/A: txt files; no raw text)

            KbFile f = new KbFile();
            f.setKbId(kbId);
            f.setName(name);
            f.setExt("txt");
            f.setSizeBytes(0L);
            f.setPath(MIGRATE_DIR.resolve("files").resolve(safe(name) + ".qa.txt").toString());
            f.setStatus("processing");
            f.setStage("chunk");
            f.setChunkCount(0);
            f.setQaCount(0);
            f.setUploadedBy(1L);
            fileMapper.insert(f);
            fileTotal++;

            List<String> toEmbed = new ArrayList<String>();
            List<KbChunk> pending = new ArrayList<KbChunk>();

            // QA pairs with cleaning (answers embed the original clause text, so qa chunks
            // already carry citable policy content)
            int seq = 0;
            Set<String> seenQ = new HashSet<String>();
            JsonNode qas = doc.path("qa");
            if (qas.isArray()) {
                for (JsonNode qa : qas) {
                    String q = qa.path("q").asText("").trim();
                    String a = qa.path("a").asText("").trim();
                    if (q.isEmpty() || a.isEmpty()) { qaDropped++; continue; }
                    if (a.matches("见.{0,12}条|参见.*") && a.length() < 20) { qaDropped++; continue; }
                    if (a.length() > 2000) a = a.substring(0, 2000);
                    String key = q.toLowerCase();
                    if (seenQ.contains(key)) { qaDropped++; continue; }
                    seenQ.add(key);
                    KbChunk ch = chunkRow(kbId, f.getId(), "qa", seq++, q + "\n" + a, q, name, category, clauseOf(a));
                    toEmbed.add(q + "\n" + a);
                    pending.add(ch);
                }
            }

            if (toEmbed.isEmpty()) {
                f.setStatus("failed");
                f.setErrorMsg("no content");
                fileMapper.updateById(f);
                continue;
            }

            f.setStage("embed");
            fileMapper.updateById(f);
            List<float[]> vectors = embedding.embed(toEmbed);
            f.setStage("store");
            fileMapper.updateById(f);
            int nRaw = 0, nQa = toEmbed.size();
            for (int i = 0; i < pending.size(); i++) {
                KbChunk ch = pending.get(i);
                ch.setEmbedding(KbEmbeddingService.toBlob(vectors.get(i)));
                chunkMapper.insert(ch);
            }
            f.setStatus("done");
            f.setStage(null);
            f.setChunkCount(nRaw);
            f.setQaCount(nQa);
            fileMapper.updateById(f);
            chunkTotal += nRaw;
            qaTotal += nQa;
            log.info("migrated doc {}: chunks={} qa={}", name, nRaw, nQa);
        }

        kb.setFileCount(fileTotal);
        kb.setChunkCount(chunkTotal);
        kb.setQaCount(qaTotal);
        kbMapper.updateById(kb);
        VectorCache.invalidate(kb.getId());
        log.info("kb migration summary: files={} rawChunks={} qa={} dropped={}",
                fileTotal, chunkTotal, qaTotal, qaDropped);
    }

    private KbChunk chunkRow(Long kbId, Long fileId, String type, int seq, String content,
                             String question, String docName, String category, String clause) {
        KbChunk c = new KbChunk();
        c.setKbId(kbId);
        c.setFileId(fileId);
        c.setChunkType(type);
        c.setSeq(seq);
        c.setContent(content);
        c.setQaQuestion(question);
        c.setDocName(docName);
        c.setCategory(category);
        c.setClauseNo(clause);
        return c;
    }

    /** Extract "第X章 ...）第X条" style locator from a QA answer, best-effort. */
    private static String clauseOf(String answer) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("（(第[一二三四五六七八九十百零\\d]+[^）]{0,20})）(第[一二三四五六七八九十百零\\d]+条)?")
                .matcher(answer);
        if (m.find()) {
            String chap = m.group(1);
            String art = m.group(2) == null ? "" : m.group(2);
            return (art.isEmpty() ? chap : chap + " · " + art).trim();
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(第[一二三四五六七八九十百零\\d]+条)").matcher(answer);
        return m2.find() ? m2.group(1) : "";
    }

    private static String safe(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    private void setFlag(String v) {
        try {
            configService.updateByKey("kb.migrate.enabled", v);
        } catch (Exception ignore) {}
    }
}
