package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.LoginDTO;
import com.wxut.agentplaza.dto.RegisterDTO;
import com.wxut.agentplaza.service.AuthService;
import com.wxut.agentplaza.vo.LoginVO;
import com.wxut.agentplaza.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(authService.register(dto));
    }

    @GetMapping("/info")
    @Operation(summary = "获取当前用户信息")
    public Result<UserInfoVO> info(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return Result.success(authService.getCurrentUser(userId));
    }
}
