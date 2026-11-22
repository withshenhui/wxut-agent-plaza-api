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
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long fileId;

    /** raw=原文块 | qa=问答对 */
    private String chunkType;

    private Integer seq;

    private String content;

    /** qa only */
    private String qaQuestion;

    private String docName;

    private String category;

    private String clauseNo;

    /** 2560-dim float32 little-endian */
    private byte[] embedding;

    private LocalDateTime createdAt;
}
