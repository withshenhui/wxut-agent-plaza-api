package com.wxut.agentplaza.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 桌面客户端安装包（XixiBuddy 产品页下载源） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("client_build")
public class ClientBuild {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** mac / win / linux */
    private String platform;

    /** arm64 / x64 / 自动 */
    private String arch;

    private String version;

    /** 展示名，如「macOS（Apple Silicon）」 */
    private String displayName;

    private String fileName;

    private Long fileSize;

    /** 服务器存储路径 */
    private String filePath;

    private String notes;

    /** 1=上架（页面可见可下载） 0=下架 */
    private Integer status;

    /** 排序权重，越小越靠前 */
    private Integer sortOrder;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
