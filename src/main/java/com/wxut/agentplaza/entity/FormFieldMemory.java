package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户填报记忆（User Memory）：按「用户 + 模板 + 字段」沉淀用户填过的内容，
 * 用于下次进入模板时的智能预填（Smart Prefill）与 AI 起草的上下文参考。
 */
@Data
@TableName("form_field_memory")
public class FormFieldMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户账号（工号/用户名） */
    private String username;

    private Long templateId;

    private String fieldKey;

    private String fieldLabel;

    /** 用户填写的原文（列名用 content 规避 MySQL 保留字 value） */
    private String content;

    private Integer useCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
