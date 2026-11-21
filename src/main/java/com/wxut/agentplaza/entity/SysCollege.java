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
@TableName("sys_college")
public class SysCollege {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String code;

    private String name;

    /** 1=数据范围全校的虚拟行（id=0 全校） */
    private Integer scopeAll;

    private Integer sort;

    private LocalDateTime createdAt;
}
