package com.wxut.agentplaza.vo;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private UserInfoVO userInfo;
}
