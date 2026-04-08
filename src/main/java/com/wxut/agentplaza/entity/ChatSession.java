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
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long agentId;

    private Long userId;

    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
