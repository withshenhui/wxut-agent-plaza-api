package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.SysConfig;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysConfigMapper;
import com.wxut.agentplaza.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements SysConfigService {

    private final SysConfigMapper configMapper;

    @Override
    public List<SysConfig> listAll() {
        return configMapper.selectList(null);
    }

    @Override
    public SysConfig getByKey(String key) {
        SysConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (config == null) throw new BusinessException("配置项不存在");
        return config;
    }

    @Override
    public void updateByKey(String key, String value) {
        SysConfig config = getByKey(key);
        config.setConfigValue(value);
        configMapper.updateById(config);
    }
}
