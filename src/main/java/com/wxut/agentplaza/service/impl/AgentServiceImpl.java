package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.AgentCreateDTO;
import com.wxut.agentplaza.dto.AgentQueryDTO;
import com.wxut.agentplaza.entity.Agent;
import com.wxut.agentplaza.entity.AgentCategory;
import com.wxut.agentplaza.entity.AgentTag;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.AgentCategoryMapper;
import com.wxut.agentplaza.mapper.AgentMapper;
import com.wxut.agentplaza.mapper.AgentTagMapper;
import com.wxut.agentplaza.service.AgentService;
import com.wxut.agentplaza.vo.AgentDetailVO;
import com.wxut.agentplaza.vo.AgentVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final AgentTagMapper agentTagMapper;
    private final AgentCategoryMapper categoryMapper;

    @Override
    public PageResult<AgentVO> search(AgentQueryDTO dto) {
        Page<Agent> page = new Page<>(dto.getPage(), dto.getSize());
        Page<Agent> result = agentMapper.searchAgents(page, dto.getCategoryId(), dto.getKeyword());
        Map<Long, String> categoryMap = getCategoryMap();
        Map<Long, List<String>> tagMap = getTagMap(result.getRecords());
        List<AgentVO> vos = result.getRecords().stream().map(a -> toAgentVO(a, categoryMap, tagMap)).collect(Collectors.toList());
        PageResult<AgentVO> pr = new PageResult<>();
        pr.setRecords(vos);
        pr.setTotal(result.getTotal());
        pr.setPage(result.getCurrent());
        pr.setSize(result.getSize());
        return pr;
    }

    @Override
    public AgentDetailVO getDetail(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) throw new BusinessException("智能体不存在");
        agent.setVisitCount(agent.getVisitCount() + 1);
        agentMapper.updateById(agent);
        AgentDetailVO vo = new AgentDetailVO();
        vo.setId(agent.getId());
        vo.setName(agent.getName());
        vo.setIcon(agent.getIcon());
        vo.setDescription(agent.getDescription());
        vo.setDetail(agent.getDetail());
        vo.setExternalUrl(agent.getExternalUrl());
        vo.setIsRecommended(agent.getIsRecommended());
        vo.setVisitCount(agent.getVisitCount());
        List<AgentTag> tags = agentTagMapper.selectList(
                new LambdaQueryWrapper<AgentTag>().eq(AgentTag::getAgentId, id));
        vo.setTags(tags.stream().map(AgentTag::getTagName).collect(Collectors.toList()));
        if (agent.getCategoryId() != null) {
            AgentCategory cat = categoryMapper.selectById(agent.getCategoryId());
            if (cat != null) {
                vo.setCategoryKey(cat.getCategoryKey());
                vo.setCategoryName(cat.getLabel());
            }
        }
        return vo;
    }

    @Override
    public List<AgentVO> listRecommended() {
        List<Agent> agents = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>().eq(Agent::getIsRecommended, 1).eq(Agent::getStatus, 1).orderByAsc(Agent::getSortOrder));
        Map<Long, String> categoryMap = getCategoryMap();
        Map<Long, List<String>> tagMap = getTagMap(agents);
        return agents.stream().map(a -> toAgentVO(a, categoryMap, tagMap)).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void create(AgentCreateDTO dto) {
        Agent agent = new Agent();
        agent.setName(dto.getName());
        agent.setIcon(dto.getIcon());
        agent.setDescription(dto.getDescription());
        agent.setDetail(dto.getDetail());
        agent.setCategoryId(dto.getCategoryId());
        agent.setExternalUrl(dto.getExternalUrl());
        agent.setIsRecommended(dto.getIsRecommended() != null ? dto.getIsRecommended() : 0);
        agent.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        agentMapper.insert(agent);
        saveTags(agent.getId(), dto.getTags());
    }

    @Override
    @Transactional
    public void update(Long id, AgentCreateDTO dto) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) throw new BusinessException("智能体不存在");
        agent.setName(dto.getName());
        agent.setIcon(dto.getIcon());
        agent.setDescription(dto.getDescription());
        agent.setDetail(dto.getDetail());
        agent.setCategoryId(dto.getCategoryId());
        agent.setExternalUrl(dto.getExternalUrl());
        agent.setIsRecommended(dto.getIsRecommended());
        agent.setSortOrder(dto.getSortOrder());
        agentMapper.updateById(agent);
        agentTagMapper.delete(new LambdaQueryWrapper<AgentTag>().eq(AgentTag::getAgentId, id));
        saveTags(id, dto.getTags());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        agentMapper.deleteById(id);
        agentTagMapper.delete(new LambdaQueryWrapper<AgentTag>().eq(AgentTag::getAgentId, id));
    }

    private void saveTags(Long agentId, List<String> tags) {
        if (tags != null) {
            for (String tag : tags) {
                AgentTag at = new AgentTag();
                at.setAgentId(agentId);
                at.setTagName(tag);
                agentTagMapper.insert(at);
            }
        }
    }

    private Map<Long, String> getCategoryMap() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(AgentCategory::getId, AgentCategory::getLabel));
    }

    private Map<Long, List<String>> getTagMap(List<Agent> agents) {
        if (agents.isEmpty()) return Map.of();
        List<Long> ids = agents.stream().map(Agent::getId).collect(Collectors.toList());
        List<AgentTag> allTags = agentTagMapper.selectList(
                new LambdaQueryWrapper<AgentTag>().in(AgentTag::getAgentId, ids));
        return allTags.stream().collect(Collectors.groupingBy(AgentTag::getAgentId,
                Collectors.mapping(AgentTag::getTagName, Collectors.toList())));
    }

    private AgentVO toAgentVO(Agent a, Map<Long, String> categoryMap, Map<Long, List<String>> tagMap) {
        AgentVO vo = new AgentVO();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setIcon(a.getIcon());
        vo.setDescription(a.getDescription());
        vo.setTags(tagMap.getOrDefault(a.getId(), List.of()));
        vo.setIsRecommended(a.getIsRecommended());
        vo.setVisitCount(a.getVisitCount());
        if (a.getCategoryId() != null) {
            vo.setCategoryKey(categoryMap.keySet().stream()
                    .filter(k -> k.equals(a.getCategoryId())).findFirst()
                    .map(k -> categoryMapper.selectById(k)).map(AgentCategory::getCategoryKey).orElse(null));
            vo.setCategoryName(categoryMap.get(a.getCategoryId()));
        }
        return vo;
    }
}
