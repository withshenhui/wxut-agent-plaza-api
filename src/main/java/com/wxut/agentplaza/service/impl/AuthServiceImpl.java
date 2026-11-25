package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.dto.LoginDTO;
import com.wxut.agentplaza.dto.RegisterDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.WecomUserMap;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.mapper.WecomUserMapMapper;
import com.wxut.agentplaza.security.JwtTokenProvider;
import com.wxut.agentplaza.service.AuthService;
import com.wxut.agentplaza.service.CollegeSyncService;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.wecom.WecomUserResolver;
import com.wxut.agentplaza.vo.LoginVO;
import com.wxut.agentplaza.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final CollegeSyncService collegeSyncService;
    private final PermissionService permissionService;
    private final com.wxut.agentplaza.mapper.SysCollegeMapper collegeMapper;
    private final WecomUserMapMapper wecomUserMapMapper;

    @Value("${jwt.expiration}")
    private long expiration;

    private void fillInfo(UserInfoVO info, SysUser user) {
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setNickname(user.getNickname());
        info.setAvatar(user.getAvatar());
        info.setRole(user.getRole());
        info.setCollegeId(user.getCollegeId());
        if (user.getCollegeId() != null) {
            com.wxut.agentplaza.entity.SysCollege c = collegeMapper.selectById(user.getCollegeId());
            info.setCollegeName(c != null ? c.getName() : null);
        }
        java.util.Map<String, Object> scope = permissionService.scope(user.getId());
        info.setDataScope((String) scope.get("scope"));
        info.setPerms(new java.util.ArrayList<>(permissionService.perms(user.getId())));
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        // 已同步用户的辅导员权限幂等补跑（与 CAS 链一致；数仓不可达静默跳过）
        collegeSyncService.syncIfNeeded(user, false);
        boolean chatAll = "all".equals(permissionService.scope(user.getId()).get("scope"));
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getCollegeId(), chatAll);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(expiration / 1000);
        UserInfoVO info = new UserInfoVO();
        fillInfo(info, user);
        vo.setUserInfo(info);
        return vo;
    }

    @Override
    public LoginVO register(RegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        userMapper.insert(user);

        boolean chatAll = "all".equals(permissionService.scope(user.getId()).get("scope"));
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getCollegeId(), chatAll);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(expiration / 1000);
        UserInfoVO info = new UserInfoVO();
        fillInfo(info, user);
        vo.setUserInfo(info);
        return vo;
    }

    @Override
    public UserInfoVO getCurrentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        UserInfoVO info = new UserInfoVO();
        fillInfo(info, user);
        return info;
    }

    @Override
    public SysUser findOrCreateCasUser(String uid, String cn) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, uid));
        if (user != null) {
            if (user.getStatus() == 0) {
                throw new BusinessException("账号已被禁用");
            }
            // 学院自动同步（幂等；数仓不可达不阻塞登录）
            user = collegeSyncService.syncIfNeeded(user, false);
            return user;
        }
        user = new SysUser();
        user.setUsername(uid);
        user.setNickname(cn != null ? cn : uid);
        user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        user.setRole("user");
        user.setStatus(1);
        userMapper.insert(user);
        user = collegeSyncService.syncIfNeeded(user, false);
        return user;
    }

    @Override
    public LoginVO loginByWecom(String wecomUserId) {
        String resolved = WecomUserResolver.resolveUsername(
                wecomUserId,
                (k) -> {
                    SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, k));
                    return u == null ? null : u.getUsername();
                },
                (k) -> {
                    WecomUserMap m = wecomUserMapMapper.selectById(k);
                    return m == null ? null : m.getUsername();
                });
        // 未命中映射不降游客：企微 userid 即工号，直接作为 username 自动建号
        String username = (resolved != null && !resolved.isEmpty()) ? resolved : wecomUserId;
        if (username == null || username.isEmpty()) throw new BusinessException("wecom_unmapped");
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user != null) {
            if (user.getStatus() == 0) throw new BusinessException("账号已被禁用");
            user = collegeSyncService.syncIfNeeded(user, false);
        } else {
            // 首次登录的新用户自动建号：置「待分配」——分配权限/学院前仅「制度建设」可见
            user = new SysUser();
            user.setUsername(username);
            user.setNickname(username);
            user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            user.setRole("user");
            user.setStatus(1);
            user.setPending(1);
            userMapper.insert(user);
            user = collegeSyncService.syncIfNeeded(user, false);
        }
        boolean chatAll = "all".equals(permissionService.scope(user.getId()).get("scope"));
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole(),
                user.getCollegeId(), chatAll);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(expiration / 1000);
        UserInfoVO info = new UserInfoVO();
        fillInfo(info, user);
        vo.setUserInfo(info);
        return vo;
    }
}
