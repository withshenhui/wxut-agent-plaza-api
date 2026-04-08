package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.annotation.OperationLog;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.UserDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

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
