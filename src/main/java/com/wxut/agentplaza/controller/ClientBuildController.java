package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.ClientBuild;
import com.wxut.agentplaza.entity.SysOperationLog;
import com.wxut.agentplaza.mapper.ClientBuildMapper;
import com.wxut.agentplaza.service.SysOperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面客户端安装包管理（XixiBuddy 产品页下载源）。
 * 列表与下载公开（产品页免登录获取），上传/编辑/删除仅管理员。
 */
@Tag(name = "客户端管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/client-builds")
@RequiredArgsConstructor
public class ClientBuildController {

    /** 安装包存储目录 */
    public static final String DOWNLOAD_DIR = "/app/aicenter-downloads";

    private final ClientBuildMapper mapper;
    private final SysOperationLogService logService;

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private String usernameOf(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return "";
        try {
            return String.valueOf(auth.getPrincipal());
        } catch (Exception e) {
            return "";
        }
    }

    // ================= 公开接口 =================

    @GetMapping
    @Operation(summary = "上架中的安装包列表（公开，产品页下载源）")
    public Result<List<Map<String, Object>>> list() {
        LambdaQueryWrapper<ClientBuild> q = new LambdaQueryWrapper<ClientBuild>()
                .eq(ClientBuild::getStatus, 1)
                .orderByAsc(ClientBuild::getSortOrder)
                .orderByDesc(ClientBuild::getId);
        List<ClientBuild> list = mapper.selectList(q);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ClientBuild b : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("platform", b.getPlatform());
            m.put("arch", b.getArch());
            m.put("version", b.getVersion());
            m.put("displayName", b.getDisplayName());
            m.put("fileName", b.getFileName());
            m.put("fileSize", b.getFileSize());
            m.put("notes", b.getNotes());
            m.put("downloadUrl", "/api/v1/client-builds/" + b.getId() + "/download");
            out.add(m);
        }
        return Result.success(out);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载安装包（公开）")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) throws IOException {
        ClientBuild b = mapper.selectById(id);
        if (b == null || b.getStatus() == null || b.getStatus() != 1) {
            return ResponseEntity.notFound().build();
        }
        File f = new File(b.getFilePath());
        if (!f.exists()) {
            log.warn("客户端安装包文件缺失 id={} path={}", id, b.getFilePath());
            return ResponseEntity.notFound().build();
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(b.getFileName(), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            encoded = "XixiBuddy-setup." + extOf(b.getFileName());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    // ================= 管理接口 =================

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "上传安装包（管理员）")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam String platform,
                                              @RequestParam(required = false) String arch,
                                              @RequestParam String version,
                                              @RequestParam(required = false) String displayName,
                                              @RequestParam(required = false) String notes,
                                              @RequestParam(required = false, defaultValue = "0") Integer sortOrder,
                                              Authentication auth) throws IOException {
        if (file == null || file.isEmpty()) return Result.error(400, "请选择安装包文件");
        String original = file.getOriginalFilename() == null ? "setup" : file.getOriginalFilename();
        String ext = extOf(original);

        File dir = new File(DOWNLOAD_DIR);
        if (!dir.exists() && !dir.mkdirs()) return Result.error(500, "下载目录创建失败：" + DOWNLOAD_DIR);
        String safe = platform + "-" + version + "-" + System.currentTimeMillis() + "." + ext;
        Path target = Paths.get(DOWNLOAD_DIR, safe);
        file.transferTo(target.toFile());

        ClientBuild b = new ClientBuild();
        b.setPlatform(platform);
        b.setArch(arch == null ? "" : arch);
        b.setVersion(version);
        b.setDisplayName(displayName == null || displayName.trim().isEmpty() ? defaultDisplayName(platform, arch) : displayName.trim());
        b.setFileName(original);
        b.setFileSize(file.getSize());
        b.setFilePath(target.toString());
        b.setNotes(notes == null ? "" : notes);
        b.setStatus(1);
        b.setSortOrder(sortOrder == null ? 0 : sortOrder);
        b.setCreatedBy(usernameOf(auth));
        mapper.insert(b);

        logService.save(opLog(usernameOf(auth), "配置", "客户端管理",
                "上传安装包 " + b.getDisplayName() + " v" + version + "（" + platform + "/" + arch + "）"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.getId());
        out.put("fileName", b.getFileName());
        out.put("fileSize", b.getFileSize());
        return Result.success(out);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "编辑（版本/展示名/说明/排序/上下架）")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        ClientBuild b = mapper.selectById(id);
        if (b == null) return Result.error(404, "记录不存在");
        if (body.containsKey("version")) b.setVersion(String.valueOf(body.get("version")));
        if (body.containsKey("displayName")) b.setDisplayName(String.valueOf(body.get("displayName")));
        if (body.containsKey("notes")) b.setNotes(String.valueOf(body.get("notes")));
        if (body.containsKey("sortOrder")) b.setSortOrder(Integer.valueOf(String.valueOf(body.get("sortOrder"))));
        if (body.containsKey("status")) b.setStatus(Integer.valueOf(String.valueOf(body.get("status"))));
        mapper.updateById(b);
        logService.save(opLog(usernameOf(auth), "配置", "客户端管理",
                "更新安装包 #" + id + (body.containsKey("status") ? " status=" + body.get("status") : "")));
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "删除安装包（含文件）")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        ClientBuild b = mapper.selectById(id);
        if (b == null) return Result.success();
        try {
            Files.deleteIfExists(Paths.get(b.getFilePath()));
        } catch (IOException e) {
            log.warn("删除安装包文件失败 {}: {}", b.getFilePath(), e.getMessage());
        }
        mapper.deleteById(id);
        logService.save(opLog(usernameOf(auth), "配置", "客户端管理", "删除安装包 #" + id + " " + b.getDisplayName()));
        return Result.success();
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "全量列表（管理员，含下架）")
    public Result<List<ClientBuild>> listAll() {
        return Result.success(mapper.selectList(new LambdaQueryWrapper<ClientBuild>()
                .orderByAsc(ClientBuild::getSortOrder).orderByDesc(ClientBuild::getId)));
    }

    // ================= 工具 =================

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "bin" : name.substring(i + 1).toLowerCase();
    }

    private static String defaultDisplayName(String platform, String arch) {
        if ("mac".equals(platform)) return "macOS（" + ("x64".equals(arch) ? "Intel" : "Apple Silicon") + "）";
        if ("win".equals(platform)) return "Windows（" + ("x64".equals(arch) ? "64 位" : "32 位") + "）";
        if ("linux".equals(platform)) return "Linux";
        return platform;
    }

    private SysOperationLog opLog(String username, String operation, String target, String detail) {
        SysOperationLog l = new SysOperationLog();
        l.setUsername(username);
        l.setOperation(operation + " · " + target);
        l.setMethod("CLIENT-BUILD");
        l.setParams(detail);
        l.setCreateTime(LocalDateTime.now());
        return l;
    }
}
