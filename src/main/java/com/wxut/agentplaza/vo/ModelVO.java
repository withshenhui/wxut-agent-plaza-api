package com.wxut.agentplaza.vo;

import lombok.Data;
import java.util.List;

@Data
public class ModelVO {
    private Long id;
    private String name;
    private String provider;
    private String type;
    private String description;
    private String releaseDate;
    private String category;
    private String apiDocsUrl;
    private String tryoutUrl;
    private String iconUrl;
    private List<String> tags;
}
