package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb")
public class Kb {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    /** private | dept | public */
    private String visibility;

    /** visibility=dept 时创建人所在学院 id */
    private Integer deptId;

    /** ok | pending */
    private String status;

    private Integer fileCount;

    private Integer chunkCount;

    private Integer qaCount;

    private Long sizeBytes;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
