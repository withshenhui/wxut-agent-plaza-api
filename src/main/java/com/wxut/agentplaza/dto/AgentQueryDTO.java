package com.wxut.agentplaza.dto;

import lombok.Data;

@Data
public class AgentQueryDTO {
    private Long categoryId;
    private String keyword;
    private Integer page = 1;
    private Integer size = 20;
    /** 管理端传 1：包含"建设中"的智能体；不传（前台）只返回已上架 */
    private Integer includeBuilding;
}
