package com.wxut.agentplaza.service;

import com.wxut.agentplaza.entity.SysConfig;
import java.util.List;

public interface SysConfigService {
    List<SysConfig> listAll();
    SysConfig getByKey(String key);
    void updateByKey(String key, String value);
}
