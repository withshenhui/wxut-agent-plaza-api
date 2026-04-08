package com.wxut.agentplaza.service;

import com.wxut.agentplaza.entity.AgentCategory;
import java.util.List;

public interface AgentCategoryService {
    List<AgentCategory> listAll();
    AgentCategory getById(Long id);
    void create(AgentCategory category);
    void update(Long id, AgentCategory category);
    void delete(Long id);
}
