package com.wxut.agentplaza.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class AgentCreateDTO {
    @NotBlank(message = "名称不能为空")
    private String name;
    private String icon;
    private String description;
    private String detail;
    private Long categoryId;
    private String externalUrl;
    private Integer isRecommended;
    private Integer sortOrder;
    private Integer status;
    private List<String> tags;
}
