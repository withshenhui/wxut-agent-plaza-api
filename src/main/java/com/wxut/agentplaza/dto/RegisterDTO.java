package com.wxut.agentplaza.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50)
    private String username;
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100)
    private String password;
    private String nickname;
    private String email;
    private String phone;
}
