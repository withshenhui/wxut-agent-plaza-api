package com.wxut.agentplaza.vo;

import lombok.Data;

import java.util.List;

@Data
public class UserInfoVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String role;
    private Integer collegeId;
    private String collegeName;
    /** 数据范围 college|all */
    private String dataScope;
    /** 功能权限键集合（仅本人可见） */
    private List<String> perms;
}
