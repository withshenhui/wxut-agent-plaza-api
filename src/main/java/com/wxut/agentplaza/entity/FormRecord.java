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
@TableName("form_record")
public class FormRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long templateId;

    private String templateName;

    private String teacherName;

    private String teacherId;

    private Integer fillCount;

    private Integer totalCount;

    /** draft=进行中 todo=待补充 done=已完成 */
    private String status;

    private String exportFormats;

    private String fieldsJson;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
