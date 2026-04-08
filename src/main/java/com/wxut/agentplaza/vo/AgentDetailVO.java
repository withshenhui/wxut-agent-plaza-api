package com.wxut.agentplaza.vo;

import lombok.Data;
import java.util.List;

@Data
public class AgentDetailVO {
    private Long id;
    private String name;
    private String icon;
    private String description;
    private String detail;
    private String externalUrl;
    private List<String> tags;
    private String categoryKey;
    private String categoryName;
    private Integer isRecommended;
    private Long visitCount;
}
