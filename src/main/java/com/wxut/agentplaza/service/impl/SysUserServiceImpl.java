package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.UserDTO;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.PermissionService;
import com.wxut.agentplaza.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final com.wxut.agentplaza.mapper.SysUserPermMapper permMapper;
    private final PermissionService permissionService;

    @Override
    public PageResult<SysUser> listUsers(String keyword, Integer status, int page, int size) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getNickname, keyword));
        }
        if (status != null) wrapper.eq(SysUser::getStatus, status);
        wrapper.orderByDesc(SysUser::getCreatedAt);
        return PageResult.of(userMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @Override
    public SysUser getUserById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }

    @Override
    public void updateUser(Long id, UserDTO dto) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        if (dto.getNickname() != null) user.setNickname(dto.getNickname());
        if (dto.getEmail() != null) user.setEmail(dto.getEmail());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());
        if (dto.getRole() != null) user.setRole(dto.getRole());
        if (dto.getStatus() != null) user.setStatus(dto.getStatus());
        if (dto.getAvatar() != null) user.setAvatar(dto.getAvatar());
        if (dto.getCollegeId() != null) user.setCollegeId(dto.getCollegeId());
        // 管理员分配学院或权限（全量 perms 覆盖）后自动解除「待分配」
        if (dto.getCollegeId() != null || dto.getPerms() != null) {
            user.setPending(0);
        }
        userMapper.updateById(user);
        // 个人权限覆盖：全量重写（仅显式传入 perms 时）
        if (dto.getPerms() != null) {
            permMapper.delete(new LambdaQueryWrapper<com.wxut.agentplaza.entity.SysUserPerm>()
                    .eq(com.wxut.agentplaza.entity.SysUserPerm::getUserId, id)
                    .eq(com.wxut.agentplaza.entity.SysUserPerm::getSource, "manual"));
            for (Map.Entry<String, Boolean> e : dto.getPerms().entrySet()) {
                // 与角色默认一致的不落库（省行数）；仅记差异
                com.wxut.agentplaza.entity.SysUserPerm p = new com.wxut.agentplaza.entity.SysUserPerm();
                p.setUserId(id);
                p.setPermKey(e.getKey());
                p.setGranted(Boolean.TRUE.equals(e.getValue()) ? 1 : 0);
                p.setSource("manual");
                permMapper.insert(p);
            }
            permissionService.evict(id);
        } else {
            permissionService.evict(id);
        }
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        user.setStatus(status);
        userMapper.updateById(user);
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Override
    public void setPending(Long id, Integer pending) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        user.setPending(pending);
        userMapper.updateById(user);
        // pending 变化影响 perms 计算，清缓存
        permissionService.evict(id);
    }

    @Override
    public void updateProfile(Long userId, UserDTO dto) {
        SysUser user = userMapper.selectById(userId);
        if (dto.getNickname() != null) user.setNickname(dto.getNickname());
        if (dto.getAvatar() != null) user.setAvatar(dto.getAvatar());
        if (dto.getEmail() != null) user.setEmail(dto.getEmail());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());
        userMapper.updateById(user);
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("旧密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }
}
