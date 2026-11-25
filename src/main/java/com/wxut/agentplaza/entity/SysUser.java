package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    private String email;

    private String phone;

    private String role = "user";

    private Integer status = 1;

    private Integer collegeId;

    private LocalDateTime collegeSyncedAt;

    /** 待分配标记：1=新用户尚未分配权限/学院（仅「制度建设」可见），0=正常 */
    private Integer pending;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
