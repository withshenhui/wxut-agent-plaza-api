package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.ModelQueryDTO;
import com.wxut.agentplaza.entity.Model;
import com.wxut.agentplaza.entity.ModelTag;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.ModelMapper;
import com.wxut.agentplaza.mapper.ModelTagMapper;
import com.wxut.agentplaza.service.ModelService;
import com.wxut.agentplaza.vo.ModelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    private final ModelMapper modelMapper;
    private final ModelTagMapper modelTagMapper;

    @Override
    public PageResult<ModelVO> search(ModelQueryDTO dto) {
        Page<Model> page = new Page<>(dto.getPage(), dto.getSize());
        Page<Model> result = modelMapper.searchModels(page, dto.getCategory(), dto.getKeyword());
        Map<Long, List<String>> tagMap = getTagMap(result.getRecords());
        List<ModelVO> vos = result.getRecords().stream().map(m -> toModelVO(m, tagMap)).collect(Collectors.toList());
        PageResult<ModelVO> pr = new PageResult<>();
        pr.setRecords(vos);
        pr.setTotal(result.getTotal());
        pr.setPage(result.getCurrent());
        pr.setSize(result.getSize());
        return pr;
    }

    @Override
    public ModelVO getDetail(Long id) {
        Model model = modelMapper.selectById(id);
        if (model == null) throw new BusinessException("模型不存在");
        return toModelVO(model, getTagMap(List.of(model)));
    }

    @Override
    @Transactional
    public void create(ModelVO dto) {
        Model model = new Model();
        copyDtoToEntity(dto, model);
        modelMapper.insert(model);
        saveTags(model.getId(), dto.getTags());
    }

    @Override
    @Transactional
    public void update(Long id, ModelVO dto) {
        Model model = modelMapper.selectById(id);
        if (model == null) throw new BusinessException("模型不存在");
        copyDtoToEntity(dto, model);
        modelMapper.updateById(model);
        modelTagMapper.delete(new LambdaQueryWrapper<ModelTag>().eq(ModelTag::getModelId, id));
        saveTags(id, dto.getTags());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        modelMapper.deleteById(id);
        modelTagMapper.delete(new LambdaQueryWrapper<ModelTag>().eq(ModelTag::getModelId, id));
    }

    private void copyDtoToEntity(ModelVO dto, Model model) {
        model.setName(dto.getName());
        model.setProvider(dto.getProvider());
        model.setType(dto.getType());
        model.setDescription(dto.getDescription());
        model.setReleaseDate(dto.getReleaseDate());
        model.setCategory(dto.getCategory() != null ? dto.getCategory() : "general");
        model.setApiDocsUrl(dto.getApiDocsUrl());
        model.setTryoutUrl(dto.getTryoutUrl());
        model.setIconUrl(dto.getIconUrl());
    }

    private void saveTags(Long modelId, List<String> tags) {
        if (tags != null) {
            for (String tag : tags) {
                ModelTag mt = new ModelTag();
                mt.setModelId(modelId);
                mt.setTagName(tag);
                modelTagMapper.insert(mt);
            }
        }
    }

    private Map<Long, List<String>> getTagMap(List<Model> models) {
        if (models.isEmpty()) return Map.of();
        List<Long> ids = models.stream().map(Model::getId).collect(Collectors.toList());
        List<ModelTag> allTags = modelTagMapper.selectList(
                new LambdaQueryWrapper<ModelTag>().in(ModelTag::getModelId, ids));
        return allTags.stream().collect(Collectors.groupingBy(ModelTag::getModelId,
                Collectors.mapping(ModelTag::getTagName, Collectors.toList())));
    }

    private ModelVO toModelVO(Model m, Map<Long, List<String>> tagMap) {
        ModelVO vo = new ModelVO();
        vo.setId(m.getId());
        vo.setName(m.getName());
        vo.setProvider(m.getProvider());
        vo.setType(m.getType());
        vo.setDescription(m.getDescription());
        vo.setReleaseDate(m.getReleaseDate());
        vo.setCategory(m.getCategory());
        vo.setApiDocsUrl(m.getApiDocsUrl());
        vo.setTryoutUrl(m.getTryoutUrl());
        vo.setIconUrl(m.getIconUrl());
        vo.setTags(tagMap.getOrDefault(m.getId(), List.of()));
        return vo;
    }
}
