package com.wxut.agentplaza.service;

import com.wxut.agentplaza.dto.LoginDTO;
import com.wxut.agentplaza.dto.RegisterDTO;
import com.wxut.agentplaza.vo.LoginVO;
import com.wxut.agentplaza.vo.UserInfoVO;

public interface AuthService {
    LoginVO login(LoginDTO dto);
    LoginVO register(RegisterDTO dto);
    UserInfoVO getCurrentUser(Long userId);
}
