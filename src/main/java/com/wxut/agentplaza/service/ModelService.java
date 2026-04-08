package com.wxut.agentplaza.service;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.AgentCreateDTO;
import com.wxut.agentplaza.dto.ModelQueryDTO;
import com.wxut.agentplaza.vo.ModelVO;

public interface ModelService {
    PageResult<ModelVO> search(ModelQueryDTO dto);
    ModelVO getDetail(Long id);
    void create(ModelVO dto);
    void update(Long id, ModelVO dto);
    void delete(Long id);
}
