package com.wxut.agentplaza.service;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.UserDTO;
import com.wxut.agentplaza.entity.SysUser;

public interface SysUserService {
    PageResult<SysUser> listUsers(String keyword, Integer status, int page, int size);
    SysUser getUserById(Long id);
    void updateUser(Long id, UserDTO dto);
    void updateStatus(Long id, Integer status);
    void resetPassword(Long id, String newPassword);
    void updateProfile(Long userId, UserDTO dto);
    void changePassword(Long userId, String oldPassword, String newPassword);
}
