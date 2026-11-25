package com.wxut.agentplaza.vo;

import lombok.Data;
import java.util.List;

@Data
public class AgentVO {
    private Long id;

    /** external | native-chat | native-rag */
    private String agentType;
    private String name;
    private String icon;
    private String description;
    /** 推荐问题（|分隔，聊天页 placeholder 轮换） */
    private String suggest;
    private List<String> tags;
    private Long categoryId;
    private String categoryKey;
    private String categoryName;
    /** 外链地址（external 类型智能体；企微仿真预览用） */
    private String externalUrl;
    private Integer isRecommended;
    private Integer isBuilding;
    private Long visitCount;
    private Integer collegeId;
    private String collegeName;
}
