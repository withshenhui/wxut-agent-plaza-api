package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.dataset.DatasetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * AI 数据工场一期：数据集 / 接入 / 清洗 / 标注任务(LabelStudio 交换) / 版本 / 导出。
 * 权限：读= data.view，写= data.manage；admin 直通。
 */
@Tag(name = "AI数据工场")
@RestController
@RequestMapping("/api/v1/dataset")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService service;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    private Long uid(Authentication auth) { return auth == null ? null : (Long) auth.getPrincipal(); }

    private SysUser userOf(Authentication auth) {
        Long id = uid(auth);
        return id == null ? null : userMapper.selectById(id);
    }

    /** needWrite=true 需 data.manage；false 需 data.view 或 data.manage。admin 直通。 */
    private boolean gate(Authentication auth, boolean needWrite) {
        SysUser u = userOf(auth);
        if (u == null) return false;
        if ("admin".equals(u.getRole())) return true;
        if (permissionService.has(u.getId(), "data.manage")) return true;
        return !needWrite && permissionService.has(u.getId(), "data.view");
    }

    private <T> Result<T> deny() { return Result.error(403, "无权使用 AI 数据工场，如需开通请联系管理员（dcc@wxut.edu.cn）"); }

    @GetMapping("/list")
    @Operation(summary = "数据集列表")
    public Result<List<Map<String, Object>>> list(Authentication auth) {
        if (!gate(auth, false)) return deny();
        SysUser u = userOf(auth);
        boolean all = "admin".equals(u.getRole()) || permissionService.has(u.getId(), "data.manage");
        return Result.success(service.list(u.getId(), all));
    }

    @PostMapping
    @Operation(summary = "创建数据集")
    public Result<Long> create(@RequestBody Map<String, String> body, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        long id = service.create(uid(auth), body.get("name"), body.get("description"), body.get("sourceType"));
        return Result.success(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据集")
    public Result<Void> delete(@PathVariable long id, Authentication auth) {
        if (!gate(auth, true)) return deny();
        service.delete(id);
        return Result.success(null);
    }

    @GetMapping("/{id}")
    @Operation(summary = "数据集详情（规则/标注任务/版本）")
    public Result<Map<String, Object>> detail(@PathVariable long id, Authentication auth) {
        if (!gate(auth, false)) return deny();
        return Result.success(service.detail(id));
    }

    @PostMapping("/{id}/upload")
    @Operation(summary = "接入：上传文件（CSV/JSON/JSONL/TXT）")
    public Result<Map<String, Object>> upload(@PathVariable long id, @RequestParam("file") MultipartFile file, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        return Result.success(service.ingestFile(id, file));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "条目分页")
    public Result<Map<String, Object>> items(@PathVariable long id, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        if (!gate(auth, false)) return deny();
        return Result.success(service.items(id, status, page, size));
    }

    @PostMapping("/{id}/clean")
    @Operation(summary = "运行清洗规则")
    public Result<Map<String, Object>> clean(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        List<Map<String, String>> rules = body == null ? null : (List<Map<String, String>>) body.get("rules");
        Map<String, Object> thresholds = body == null ? null : (Map<String, Object>) body.get("thresholds");
        return Result.success(service.runClean(id, rules, thresholds));
    }

    @PostMapping("/{id}/annot-tasks")
    @Operation(summary = "创建标注任务")
    public Result<Long> createAnnot(@PathVariable long id, @RequestBody Map<String, String> body, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        return Result.success(service.createAnnotTask(id, body.get("name"), body.get("template"), body.get("instruction"), body.get("labelConfig")));
    }

    @GetMapping("/{id}/annot-tasks/{tid}/export-ls")
    @Operation(summary = "导出 LabelStudio 导入 JSON")
    public void exportLs(@PathVariable long id, @PathVariable long tid, Authentication auth, HttpServletResponse resp) throws Exception {
        if (!gate(auth, false)) { resp.sendError(403); return; }
        String json = service.exportLsTasks(id, tid);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"labelstudio-tasks-" + tid + ".json\"");
        resp.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/annot-tasks/{tid}/import-ls")
    @Operation(summary = "导入 LabelStudio 标注结果")
    public Result<Map<String, Object>> importLs(@PathVariable long id, @PathVariable long tid, @RequestParam("file") MultipartFile file, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        return Result.success(service.importLsResult(id, tid, file));
    }

    @PostMapping("/{id}/annot-tasks/{tid}/push")
    @Operation(summary = "推送标注任务到 Label Studio")
    public Result<Map<String, Object>> pushLs(@PathVariable long id, @PathVariable long tid, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        return Result.success(service.pushToLs(id, tid));
    }

    @PostMapping("/{id}/annot-tasks/{tid}/pull")
    @Operation(summary = "从 Label Studio 拉取标注结果")
    public Result<Map<String, Object>> pullLs(@PathVariable long id, @PathVariable long tid, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        return Result.success(service.pullFromLs(id, tid));
    }

    @PostMapping("/{id}/versions")
    @Operation(summary = "创建数据集版本")
    public Result<Map<String, Object>> createVersion(@PathVariable long id, @RequestBody Map<String, Object> body, Authentication auth) throws Exception {
        if (!gate(auth, true)) return deny();
        int ratio = body.get("trainRatio") == null ? 80 : Integer.parseInt(String.valueOf(body.get("trainRatio")));
        String lineage = body.get("lineage") == null ? null : String.valueOf(body.get("lineage"));
        return Result.success(service.createVersion(id, (String) body.get("name"), ratio, lineage));
    }

    @GetMapping("/{id}/versions/export")
    @Operation(summary = "导出数据集（alpaca/sharegpt/jsonl）")
    public void exportVersion(@PathVariable long id, @RequestParam(defaultValue = "alpaca") String format, Authentication auth, HttpServletResponse resp) throws Exception {
        if (!gate(auth, false)) { resp.sendError(403); return; }
        String data = service.exportVersion(id, format);
        resp.setContentType("application/json;charset=UTF-8");
        String fn = "dataset-" + id + "-" + format + ("jsonl".equalsIgnoreCase(format) ? ".jsonl" : ".json");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fn + "\"");
        resp.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/labelstudio/ping")
    @Operation(summary = "Label Studio 连通性自检")
    public Result<Map<String, Object>> lsPing(Authentication auth) {
        if (!gate(auth, false)) return deny();
        return Result.success(service.lsPing());
    }
}
