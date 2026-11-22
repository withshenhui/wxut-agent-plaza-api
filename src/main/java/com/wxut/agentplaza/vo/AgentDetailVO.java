package com.wxut.agentplaza.vo;

import lombok.Data;
import java.util.List;

@Data
public class AgentDetailVO {
    private Long id;

    /** external | native-chat | native-rag */
    private String agentType;
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
    private Integer collegeId;
    private String collegeName;
}
