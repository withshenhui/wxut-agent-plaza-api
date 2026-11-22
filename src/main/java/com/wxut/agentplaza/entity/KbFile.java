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
@TableName("kb_file")
public class KbFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String name;

    /** pdf/docx/txt/md */
    private String ext;

    private Long sizeBytes;

    private String path;

    /** pending|processing|done|failed */
    private String status;

    /** current pipeline stage: upload|clean|chunk|qa|embed|store */
    private String stage;

    private Integer chunkCount;

    private Integer qaCount;

    private String errorMsg;

    private Long uploadedBy;

    private LocalDateTime createdAt;
}
