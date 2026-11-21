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
@TableName("sys_user_perm")
public class SysUserPerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String permKey;

    /** 1=加授 0=显式剥夺 */
    private Integer granted;

    /** manual/auto_role/auto_fdy */
    private String source;

    private LocalDateTime createdAt;
}
