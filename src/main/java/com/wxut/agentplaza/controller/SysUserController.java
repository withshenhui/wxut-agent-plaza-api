package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.UserDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.SysUserPerm;
import com.wxut.agentplaza.mapper.SysUserPermMapper;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;
    private final PermissionService permissionService;
    private final SysUserPermMapper permMapper;
    private final FeatureService featureService;

    @GetMapping
    @Operation(summary = "获取用户列表")
    public Result<PageResult<SysUser>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.success(userService.listUsers(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情")
    public Result<SysUser> getById(@PathVariable Long id) {
        return Result.success(userService.getUserById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户")
    @OperationLog("更新用户")
    public Result<Void> update(@PathVariable Long id, @RequestBody UserDTO dto) {
        userService.updateUser(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "切换用户状态")
    @OperationLog("切换用户状态")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.updateStatus(id, status);
        return Result.success();
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "重置用户密码")
    @OperationLog("重置用户密码")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestParam String password) {
        userService.resetPassword(id, password);
        return Result.success();
    }

    @GetMapping("/{id}/perms")
    @Operation(summary = "用户全部功能权限（角色默认 + 个人加授/剥夺）")
    public Result<Set<String>> perms(@PathVariable Long id) {
        return Result.success(permissionService.perms(id));
    }

    /**
     * 单项功能权限加授/剥夺（sys_user_perm upsert），用于申请开通制入口的人工授权：
     * 如 dev.entry（智能体开发平台）——老师由所在学院/部门向信息化与数据服务中心申请，
     * 审核通过后在此勾选开通。
     */
    @PutMapping("/{id}/perm")
    @Operation(summary = "设置用户单项功能权限（加授/剥夺）")
    @OperationLog("设置用户功能权限")
    public Result<Void> setPerm(@PathVariable Long id, @RequestParam String permKey,
                                @RequestParam boolean granted) {
        if (!featureService.grantablePerms().contains(permKey)) {
            return Result.error(400, "该权限项不支持后台设置：" + permKey);
        }
        SysUser u = userService.getUserById(id);
        if (u == null) {
            return Result.error(404, "用户不存在");
        }
        SysUserPerm existing = permMapper.selectOne(new LambdaQueryWrapper<SysUserPerm>()
                .eq(SysUserPerm::getUserId, id).eq(SysUserPerm::getPermKey, permKey));
        int flag = granted ? 1 : 0;
        if (existing == null) {
            SysUserPerm p = new SysUserPerm();
            p.setUserId(id);
            p.setPermKey(permKey);
            p.setGranted(flag);
            p.setSource("manual");
            p.setCreatedAt(LocalDateTime.now());
            permMapper.insert(p);
        } else {
            existing.setGranted(flag);
            existing.setSource("manual");
            permMapper.updateById(existing);
        }
        permissionService.evict(id);
        // 管理员授予权限后自动解除「待分配」
        if (granted) {
            userService.setPending(id, 0);
        }
        return Result.success();
    }

    /** 手动开关「待分配」：1=待分配（仅制度建设可见），0=正常。仅管理员（/api/v1/users/** 已限 ADMIN）。 */
    @PatchMapping("/{id}/pending")
    @Operation(summary = "设置用户「待分配」状态")
    @OperationLog("设置用户待分配状态")
    public Result<Void> setPending(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer pending = body == null ? null : body.get("pending");
        if (pending == null || (pending != 0 && pending != 1)) {
            return Result.error(400, "pending 只能为 0 或 1");
        }
        userService.setPending(id, pending);
        return Result.success();
    }

    @PutMapping("/profile")
    @Operation(summary = "更新个人资料")
    public Result<Void> updateProfile(Authentication auth, @RequestBody UserDTO dto) {
        Long userId = (Long) auth.getPrincipal();
        userService.updateProfile(userId, dto);
        return Result.success();
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码")
    public Result<Void> changePassword(Authentication auth, @RequestParam String oldPassword,
                                       @RequestParam String newPassword) {
        Long userId = (Long) auth.getPrincipal();
        userService.changePassword(userId, oldPassword, newPassword);
        return Result.success();
    }
}
