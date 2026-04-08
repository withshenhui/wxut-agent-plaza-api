package com.wxut.agentplaza.dto;

import lombok.Data;

@Data
public class UserDTO {
    private String nickname;
    private String avatar;
    private String email;
    private String phone;
    private String role;
    private Integer status;
}
