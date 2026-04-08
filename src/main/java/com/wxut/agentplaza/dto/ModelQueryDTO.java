package com.wxut.agentplaza.dto;

import lombok.Data;

@Data
public class ModelQueryDTO {
    private String category;
    private String keyword;
    private Integer page = 1;
    private Integer size = 20;
}
