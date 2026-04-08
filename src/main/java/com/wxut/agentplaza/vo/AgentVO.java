package com.wxut.agentplaza.vo;

import lombok.Data;
import java.util.List;

@Data
public class AgentVO {
    private Long id;
    private String name;
    private String icon;
    private String description;
    private List<String> tags;
    private String categoryKey;
    private String categoryName;
    private Integer isRecommended;
    private Long visitCount;
}
