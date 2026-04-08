package com.wxut.agentplaza.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatMessageVO {
    private Long id;
    private String sender;
    private String content;
    private LocalDateTime createdAt;
}
