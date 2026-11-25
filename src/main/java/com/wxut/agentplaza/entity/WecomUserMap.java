package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wecom_user_map")
public class WecomUserMap {

    @TableId
    private String userid;

    private String username;

    private LocalDateTime createdAt;
}
