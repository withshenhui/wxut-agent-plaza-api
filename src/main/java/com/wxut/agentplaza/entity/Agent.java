package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent")
public class Agent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String icon;

    private String description;

    /** 推荐问题，|分隔 */
    private String suggest;

    private String detail;

    private Long categoryId;

    private String externalUrl;

    private Integer isRecommended;

    /** 建设中：1=建设中（广场列表不展示，后台可见） */
    private Integer isBuilding;

    /** 学院归属（sys_college.id；0=全校虚拟行；null=通用智能体不受学院管控） */
    private Integer collegeId;

    private Integer status;

    private Integer sortOrder;

    private Long visitCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private List<String> tags;

    /** external | native-chat | native-rag */
    private String agentType;

    /** bound kb ids, comma separated; dept KBs auto-attached at runtime */
    private String kbIds;
}
