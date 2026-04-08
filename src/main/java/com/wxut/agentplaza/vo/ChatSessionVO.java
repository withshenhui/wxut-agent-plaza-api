package com.wxut.agentplaza.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatSessionVO {
    private String id;
    private Long agentId;
    private String agentName;
    private String agentIcon;
    private String title;
    private LocalDateTime createdAt;
    private List<ChatMessageVO> messages;
}
