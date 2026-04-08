package com.wxut.agentplaza.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wxut.agentplaza.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {

    IPage<Agent> searchAgents(IPage<Agent> page, @Param("categoryId") Long categoryId, @Param("keyword") String keyword);
}
