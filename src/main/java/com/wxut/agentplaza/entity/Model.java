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
@TableName("model")
public class Model {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String provider;

    private String type;

    private String description;

    private String releaseDate;

    private String category = "general";

    private String apiDocsUrl;

    private String tryoutUrl;

    private String iconUrl;

    private Integer status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private List<String> tags;
}
