package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysFile;
import com.wxut.agentplaza.service.SysFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;

@Tag(name = "文件管理")
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class SysFileController {

    private final SysFileService fileService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件")
    @OperationLog("上传文件")
    public Result<SysFile> upload(@RequestParam("file") MultipartFile file, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return Result.success(fileService.upload(file, userId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件信息")
    public Result<SysFile> getById(@PathVariable Long id) {
        return Result.success(fileService.getById(id));
    }

    @GetMapping("/download/{id}")
    @Operation(summary = "下载文件")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        SysFile sysFile = fileService.getById(id);
        Resource resource = new FileSystemResource(new File(sysFile.getFilePath()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sysFile.getOriginalName() + "\"")
                .contentType(MediaType.parseMediaType(sysFile.getMimeType() != null ? sysFile.getMimeType() : "application/octet-stream"))
                .body(resource);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件")
    @OperationLog("删除文件")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.success();
    }
}
