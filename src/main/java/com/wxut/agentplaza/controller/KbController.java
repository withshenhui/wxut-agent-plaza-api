package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.KbChunk;
import com.wxut.agentplaza.entity.KbFile;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.KbChunkMapper;
import com.wxut.agentplaza.mapper.KbFileMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.SysConfigService;
import com.wxut.agentplaza.service.kb.KbPipelineService;
import com.wxut.agentplaza.service.kb.KbSearchService;
import com.wxut.agentplaza.service.kb.VectorCache;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Knowledge base center APIs. JWT-protected except inter-service search (X-Kb-Token). */
@RestController
@RequestMapping("/api/v1/kb")
public class KbController {

    private static final String STORE_ROOT = "/app/aicenter-kb";
    private static final long MAX_FILE = 50L * 1024 * 1024;

    private final KbMapper kbMapper;
    private final KbFileMapper fileMapper;
    private final KbChunkMapper chunkMapper;
    private final SysUserMapper userMapper;
    private final KbPipelineService pipeline;
    private final KbSearchService searchService;
    private final SysConfigService configService;

    public KbController(KbMapper kbMapper, KbFileMapper fileMapper, KbChunkMapper chunkMapper,
                        SysUserMapper userMapper, KbPipelineService pipeline,
                        KbSearchService searchService, SysConfigService configService) {
        this.kbMapper = kbMapper; this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper; this.userMapper = userMapper;
        this.pipeline = pipeline; this.searchService = searchService;
        this.configService = configService;
    }

    // ================= helpers =================

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private SysUser userOf(Authentication auth) {
        return auth == null ? null : userMapper.selectById((Long) auth.getPrincipal());
    }

    /** private: owner; dept: dept member (read) / owner or admin (write); public: anyone read. */
    private boolean canRead(Kb kb, SysUser u, Authentication auth) {
        if (kb == null || u == null) return false;
        if (isAdmin(auth)) return true;
        if ("private".equals(kb.getVisibility())) return kb.getOwnerUserId().equals(u.getId());
        return true;
    }

    private boolean canWrite(Kb kb, SysUser u, Authentication auth) {
        if (kb == null || u == null) return false;
        if (isAdmin(auth)) return true;
        if (kb.getOwnerUserId().equals(u.getId())) return true;
        // dept library: member of the same dept
        return "dept".equals(kb.getVisibility())
                && kb.getDeptId() != null && kb.getDeptId().equals(u.getCollegeId());
    }

    private String serviceToken() {
        try {
            String v = configService.getByKey("kb.service_token").getConfigValue();
            if (v != null && !v.isEmpty()) return v;
        } catch (Exception ignore) {}
        return "";
    }

    // ================= kb CRUD =================

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestParam String tab, Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        QueryWrapper<Kb> w = new QueryWrapper<Kb>();
        if ("mine".equals(tab)) {
            w.eq("owner_user_id", u.getId());
        } else if ("dept".equals(tab)) {
            w.eq("visibility", "dept")
             .eq("dept_id", u.getCollegeId() == null ? -1 : u.getCollegeId());
        } else {
            w.eq("visibility", "public").eq("status", "ok");
        }
        w.orderByDesc("updated_at");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Kb kb : kbMapper.selectList(w)) {
            out.add(kbSummary(kb));
        }
        return Result.success(out);
    }

    private Map<String, Object> kbSummary(Kb kb) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("visibility", kb.getVisibility());
        m.put("status", kb.getStatus());
        m.put("fileCount", kb.getFileCount());
        m.put("chunkCount", kb.getChunkCount());
        m.put("qaCount", kb.getQaCount());
        m.put("sizeBytes", kb.getSizeBytes());
        return m;
    }

    @PostMapping
    public Result<Long> create(@RequestBody Map<String, String> body, Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        String name = body.get("name") == null ? "" : body.get("name").trim();
        String visibility = body.get("visibility");
        if (name.isEmpty()) return Result.error(400, "库名称不能为空");
        if (!"private".equals(visibility) && !"dept".equals(visibility) && !"public".equals(visibility)) {
            return Result.error(400, "可见范围非法");
        }
        if ("dept".equals(visibility) && u.getCollegeId() == null) {
            return Result.error(400, "当前账号无学院归属，无法创建部门库");
        }
        Kb kb = new Kb();
        kb.setName(name);
        kb.setDescription(body.get("description") == null ? "" : body.get("description"));
        kb.setOwnerUserId(u.getId());
        kb.setVisibility(visibility);
        if ("dept".equals(visibility)) kb.setDeptId(u.getCollegeId());
        kb.setStatus("public".equals(visibility) ? "pending" : "ok");
        kb.setFileCount(0); kb.setChunkCount(0); kb.setQaCount(0); kb.setSizeBytes(0L);
        kbMapper.insert(kb);
        return Result.success(kb.getId());
    }

    // ================= files =================

    @PostMapping("/{kbId}/files")
    public Result<Long> upload(@PathVariable Long kbId, @RequestParam("file") MultipartFile file,
                               Authentication auth) throws Exception {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null) return Result.error(404, "知识库不存在");
        if (!canWrite(kb, u, auth)) return Result.error(403, "无上传权限");

        String name = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!"pdf".equals(ext) && !"docx".equals(ext) && !"txt".equals(ext) && !"md".equals(ext)) {
            return Result.error(400, "仅支持 PDF/DOCX/TXT/MD");
        }
        if (file.getSize() > MAX_FILE) return Result.error(400, "单文件不超过 50MB");
        if ((kb.getSizeBytes() == null ? 0 : kb.getSizeBytes()) + file.getSize() > 500L * 1024 * 1024) {
            return Result.error(400, "库容量已达 500MB 上限");
        }

        KbFile f = new KbFile();
        f.setKbId(kbId);
        f.setName(name);
        f.setExt(ext);
        f.setSizeBytes(file.getSize());
        f.setStatus("pending");
        f.setStage("upload");
        f.setChunkCount(0); f.setQaCount(0);
        f.setUploadedBy(u.getId());
        fileMapper.insert(f);

        Path dir = Paths.get(STORE_ROOT, String.valueOf(kbId));
        Files.createDirectories(dir);
        Path target = dir.resolve(f.getId() + "." + ext);
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        f.setPath(target.toString());
        fileMapper.updateById(f);

        kb.setFileCount((kb.getFileCount() == null ? 0 : kb.getFileCount()) + 1);
        kb.setSizeBytes((kb.getSizeBytes() == null ? 0 : kb.getSizeBytes()) + file.getSize());
        kbMapper.updateById(kb);

        pipeline.enqueue(f.getId());
        return Result.success(f.getId());
    }

    @GetMapping("/{kbId}/files")
    public Result<List<Map<String, Object>>> files(@PathVariable Long kbId, Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null || !canRead(kb, u, auth)) return Result.error(403, "无权访问");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (KbFile f : fileMapper.selectList(new QueryWrapper<KbFile>()
                .eq("kb_id", kbId).orderByDesc("created_at"))) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("id", f.getId());
            m.put("name", f.getName());
            m.put("ext", f.getExt());
            m.put("sizeBytes", f.getSizeBytes());
            m.put("status", f.getStatus());
            m.put("stage", f.getStage());
            m.put("chunkCount", f.getChunkCount());
            m.put("qaCount", f.getQaCount());
            m.put("errorMsg", f.getErrorMsg());
            m.put("createdAt", f.getCreatedAt() == null ? "" : f.getCreatedAt().toString());
            out.add(m);
        }
        return Result.success(out);
    }

    @DeleteMapping("/{kbId}/files/{fileId}")
    public Result<Void> deleteFile(@PathVariable Long kbId, @PathVariable Long fileId, Authentication auth) {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null || !canWrite(kb, u, auth)) return Result.error(403, "无删除权限");
        KbFile f = fileMapper.selectById(fileId);
        if (f == null || !kbId.equals(f.getKbId())) return Result.error(404, "文件不存在");

        chunkMapper.delete(new QueryWrapper<KbChunk>().eq("file_id", fileId));
        fileMapper.deleteById(fileId);
        try { Files.deleteIfExists(Paths.get(f.getPath())); } catch (Exception ignore) {}

        kb.setFileCount(Math.max(0, (kb.getFileCount() == null ? 0 : kb.getFileCount()) - 1));
        kb.setChunkCount(Math.max(0, (kb.getChunkCount() == null ? 0 : kb.getChunkCount())
                - (f.getChunkCount() == null ? 0 : f.getChunkCount())));
        kb.setQaCount(Math.max(0, (kb.getQaCount() == null ? 0 : kb.getQaCount())
                - (f.getQaCount() == null ? 0 : f.getQaCount())));
        kb.setSizeBytes(Math.max(0L, (kb.getSizeBytes() == null ? 0 : kb.getSizeBytes())
                - (f.getSizeBytes() == null ? 0 : f.getSizeBytes())));
        kbMapper.updateById(kb);
        VectorCache.invalidate(kbId);
        return Result.success(null);
    }

    /** 文件预览：流式返回原文件（inline），前端按类型渲染（pdf=iframe / docx=mammoth / txt,md=pre） */
    @org.springframework.web.bind.annotation.GetMapping("/{kbId}/files/{fileId}/preview")
    public void preview(@PathVariable Long kbId, @PathVariable Long fileId,
                        Authentication auth, javax.servlet.http.HttpServletResponse response) throws Exception {
        SysUser u = userOf(auth);
        if (u == null) { jsonError(response, 401, "未登录或登录已过期，请重新登录后再预览"); return; }
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null || !canRead(kb, u, auth)) { jsonError(response, 403, "无权访问该知识库"); return; }
        KbFile f = fileMapper.selectById(fileId);
        if (f == null || !kbId.equals(f.getKbId())) { jsonError(response, 404, "文件不存在或已删除"); return; }
        java.io.File disk = f.getPath() == null ? null : new java.io.File(f.getPath());
        if (disk == null || !disk.exists()) { jsonError(response, 404, "存量迁移文件无原始副本，暂不支持预览；新上传的文件可预览"); return; }
        String ext = f.getExt() == null ? "" : f.getExt().toLowerCase();
        String mime = "pdf".equals(ext) ? "application/pdf"
                : "docx".equals(ext) ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "txt".equals(ext) ? "text/plain;charset=UTF-8"
                : "text/markdown;charset=UTF-8";
        response.setContentType(mime);
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(f.getName(), "UTF-8"));
        response.setContentLengthLong(disk.length());
        try (java.io.InputStream in = new java.io.FileInputStream(disk);
             java.io.OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** sendError 会被 Spring Boot 错误页吞掉 message，前端拿不到原因——直接写 JSON */
    private void jsonError(javax.servlet.http.HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + msg + "\"}");
    }

    @PostMapping("/{kbId}/files/{fileId}/rebuild")
    public Result<Void> rebuild(@PathVariable Long kbId, @PathVariable Long fileId, Authentication auth) {
        SysUser u = userOf(auth);
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null || !canWrite(kb, u, auth)) return Result.error(403, "无权限");
        KbFile f = fileMapper.selectById(fileId);
        if (f == null || !kbId.equals(f.getKbId())) return Result.error(404, "文件不存在");
        f.setStatus("pending");
        f.setStage("upload");
        f.setErrorMsg(null);
        fileMapper.updateById(f);
        pipeline.enqueue(fileId);
        return Result.success(null);
    }

    // ================= search =================

    /** In-library search (query test panel). */
    @GetMapping("/{kbId}/search")
    public Result<Map<String, Object>> searchInKb(@PathVariable Long kbId, @RequestParam String q,
                                                  Authentication auth) throws Exception {
        SysUser u = userOf(auth);
        if (u == null) return Result.error(401, "未登录");
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null || !canRead(kb, u, auth)) return Result.error(403, "无权访问");
        Map<String, Object> out = searchService.searchResult(java.util.Collections.singletonList(kbId), q, 6);
        return Result.success(out);
    }

    /**
     * Unified search. Two auth paths:
     *  - JWT (browser): scope=kb uses body kbIds (visibility checked); scope=personal auto-attaches own private KBs.
     *  - X-Kb-Token header (inter-service, e.g. 49 MCP): scope=kb with kbIds; scope=personal requires X-User-Id.
     */
    @PostMapping("/search")
    public Result<Map<String, Object>> search(@RequestHeader(value = "X-Kb-Token", required = false) String kbToken,
                                              @RequestHeader(value = "X-User-Id", required = false) Long xUserId,
                                              @RequestBody Map<String, Object> body,
                                              Authentication auth) throws Exception {
        String q = body.get("q") == null ? "" : body.get("q").toString().trim();
        String scope = body.get("scope") == null ? "kb" : body.get("scope").toString();
        int topK = 6;
        try { topK = Integer.parseInt(String.valueOf(body.get("topK"))); } catch (Exception ignore) {}
        if (q.isEmpty()) return Result.error(400, "q 不能为空");

        Long userId = null;
        Integer deptId = null;
        boolean viaService = false;
        String expected = serviceToken();
        if (expected != null && !expected.isEmpty() && expected.equals(kbToken)) {
            viaService = true;
            userId = xUserId;
        } else {
            SysUser u = userOf(auth);
            if (u == null) return Result.error(401, "未授权");
            userId = u.getId();
            deptId = u.getCollegeId();
        }

        List<Long> kbIds = new ArrayList<Long>();
        if ("personal".equals(scope)) {
            if (userId == null) return Result.error(401, "personal scope 需要 X-User-Id");
            for (Kb kb : kbMapper.selectList(new QueryWrapper<Kb>()
                    .eq("owner_user_id", userId).eq("visibility", "private"))) {
                kbIds.add(kb.getId());
            }
        } else {
            Object raw = body.get("kbIds");
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    try { kbIds.add(Long.parseLong(String.valueOf(o))); } catch (Exception ignore) {}
                }
            }
            if (!viaService) {
                // visibility check per kb
                List<Long> allowed = new ArrayList<Long>();
                for (Long id : kbIds) {
                    Kb kb = kbMapper.selectById(id);
                    if (kb != null && canRead(kb, userOf(auth), auth)) allowed.add(id);
                }
                kbIds = allowed;
            }
        }
        if (kbIds.isEmpty()) return Result.success(emptyResult());

        Map<String, Object> out = searchService.searchResult(kbIds, q, topK);
        return Result.success(out);
    }

    private Map<String, Object> emptyResult() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("hits", new ArrayList<Object>());
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("total", 0); s.put("qa", 0); s.put("raw", 0);
        m.put("summary", s);
        return m;
    }

    // ================= admin =================

    /** Approve a pending public KB (admin only). */
    @PostMapping("/admin/{kbId}/audit")
    public Result<Void> audit(@PathVariable Long kbId, @RequestParam boolean approve, Authentication auth) {
        if (!isAdmin(auth)) return Result.error(403, "仅管理员");
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null) return Result.error(404, "知识库不存在");
        kb.setStatus(approve ? "ok" : "rejected");
        kbMapper.updateById(kb);
        return Result.success(null);
    }
}
