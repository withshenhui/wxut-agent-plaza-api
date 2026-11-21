package com.wxut.agentplaza.dto;

import lombok.Data;

import java.util.Map;

@Data
public class UserDTO {
    private String nickname;
    private String avatar;
    private String email;
    private String phone;
    private String role;
    private Integer status;
    private Integer collegeId;
    /** 个人权限覆盖（仅管理端设置）：键→true加授/false剥夺 */
    private Map<String, Boolean> perms;
}
