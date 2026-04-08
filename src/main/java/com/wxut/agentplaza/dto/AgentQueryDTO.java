package com.wxut.agentplaza.dto;

import lombok.Data;

@Data
public class AgentQueryDTO {
    private Long categoryId;
    private String keyword;
    private Integer page = 1;
    private Integer size = 20;
}
