package com.wxut.agentplaza.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wxut.agentplaza.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModelMapper extends BaseMapper<Model> {

    IPage<Model> searchModels(IPage<Model> page, @Param("category") String category, @Param("keyword") String keyword);
}
