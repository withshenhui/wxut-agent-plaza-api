package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.AgentCategory;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.AgentCategoryMapper;
import com.wxut.agentplaza.service.AgentCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentCategoryServiceImpl implements AgentCategoryService {

    private final AgentCategoryMapper categoryMapper;

    @Override
    public List<AgentCategory> listAll() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<AgentCategory>().eq(AgentCategory::getStatus, 1).orderByAsc(AgentCategory::getSortOrder));
    }

    @Override
    public AgentCategory getById(Long id) {
        AgentCategory cat = categoryMapper.selectById(id);
        if (cat == null) throw new BusinessException("分类不存在");
        return cat;
    }

    @Override
    public void create(AgentCategory category) {
        categoryMapper.insert(category);
    }

    @Override
    public void update(Long id, AgentCategory category) {
        category.setId(id);
        categoryMapper.updateById(category);
    }

    @Override
    public void delete(Long id) {
        categoryMapper.deleteById(id);
    }
}
