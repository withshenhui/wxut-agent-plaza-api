package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 智能体对话用量（按天累计一次），供洞察页「近 7 天对话」统计 */
@Data
@TableName("chat_usage")
public class ChatUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    /** 统计日 */
    private LocalDate statDate;

    private LocalDateTime createdAt;
}
