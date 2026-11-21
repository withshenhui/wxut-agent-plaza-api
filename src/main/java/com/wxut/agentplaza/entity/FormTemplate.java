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
@TableName("form_template")
public class FormTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** preset=预置 / custom=上传 */
    private String source;

    /** docx/xlsx/pdf */
    private String fileType;

    private String fileName;

    /** 模板原文件路径（可导出 Word 的实模板）；classpath: 前缀表示 war 内预置资源 */
    private String filePath;

    /** 字段定义数组 JSON */
    private String fieldsJson;

    private Integer fieldCount;

    /** 1=启用 0=停用 */
    private Integer status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
