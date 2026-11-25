package com.wxut.agentplaza.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxut.agentplaza.entity.ChatUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChatUsageMapper extends BaseMapper<ChatUsage> {

    /** 近 N 天对话次数（按天，真实统计） */
    @Select("SELECT stat_date AS d, COUNT(*) AS n FROM chat_usage WHERE stat_date >= #{from} GROUP BY stat_date ORDER BY stat_date")
    List<Map<String, Object>> chatByDay(@Param("from") LocalDate from);

    /** 各学院智能体访问量（真实：agent.visit_count 按学院聚合） */
    @Select("SELECT c.name AS name, COALESCE(SUM(a.visit_count),0) AS n " +
            "FROM agent a JOIN sys_college c ON a.college_id = c.id " +
            "WHERE a.college_id IS NOT NULL " +
            "GROUP BY c.name ORDER BY n DESC LIMIT 12")
    List<Map<String, Object>> collegeActivity();

    /** 场景/分类访问量（真实：agent.visit_count 按分类聚合） */
    @Select("SELECT cat.label AS label, COALESCE(SUM(a.visit_count),0) AS n " +
            "FROM agent a JOIN agent_category cat ON a.category_id = cat.id " +
            "GROUP BY cat.label ORDER BY n DESC LIMIT 10")
    List<Map<String, Object>> sceneActivity();
}
