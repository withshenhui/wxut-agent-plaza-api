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
@TableName("v2_analysis")
public class V2Analysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 场景：funding/safety/academic/mental */
    private String scene;

    private String studentId;

    private String studentName;

    private String question;

    private String content;

    private String model;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
