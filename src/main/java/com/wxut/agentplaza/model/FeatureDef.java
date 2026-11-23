package com.wxut.agentplaza.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FeatureDef {
    private String key;
    private String label;
    private String group;
    private String kind;        // page|channel|external|perm|route
    private String route;
    private String href;
    private String api;         // 后端 URL 前缀（可空）
    private String gate;        // guest|login|perm
    private String permKey;
    private boolean enabled = true;
    private boolean navHidden = false;
    private Map<String, Boolean> defaults;
    private List<FeatureDef> children;
}
