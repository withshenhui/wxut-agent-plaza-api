package com.wxut.agentplaza.service;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.AgentCreateDTO;
import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.vo.AgentDetailVO;
import com.wxut.agentplaza.vo.AgentVO;
import java.util.List;

public interface AgentService {
    PageResult<AgentVO> search(AgentQueryDTO dto);
    AgentDetailVO getDetail(Long id);
    List<AgentVO> listRecommended();
    void create(AgentCreateDTO dto);
    void update(Long id, AgentCreateDTO dto);
    void delete(Long id);
}
