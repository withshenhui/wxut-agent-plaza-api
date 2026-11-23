package com.wxut.agentplaza.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.SysConfig;
import com.wxut.agentplaza.model.FeatureDef;
import com.wxut.agentplaza.service.FeatureService;
import com.wxut.agentplaza.service.SysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeatureServiceImpl implements FeatureService {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String KEY = "v2.features";
    private static final long TTL_MS = 60_000L;

    @Autowired(required = false) private SysConfigService configService;

    private volatile List<FeatureDef> cached;
    private volatile long loadedAt;
    private final List<FeatureDef> builtIn = new ArrayList<>();
    private volatile boolean builtInLoaded;

    /** 供单测注入 null 构造（只用内置默认） */
    public FeatureServiceImpl(SysConfigService configService) { this.configService = configService; }

    public FeatureServiceImpl() {}

    @PostConstruct
    void loadBuiltIn() {
        ensureBuiltIn();
    }

    /** 惰性加载内置默认（Spring 走 @PostConstruct；纯单测直接 new 时由 resolve() 兜底触发）。 */
    private void ensureBuiltIn() {
        if (builtInLoaded) return;
        synchronized (this) {
            if (builtInLoaded) return;
            try (InputStream in = new ClassPathResource("feature-defaults.json").getInputStream()) {
                Map<?,?> root = OM.readValue(in, Map.class);
                builtIn.addAll(parse((List<?>) root.get("features")));
            } catch (Exception e) {
                throw new IllegalStateException("feature-defaults.json 加载失败", e);
            }
            builtInLoaded = true;
        }
    }

    @SuppressWarnings("unchecked")
    private List<FeatureDef> parse(List<?> arr) {
        if (arr == null) return Collections.emptyList();
        return arr.stream().map(o -> OM.convertValue(o, FeatureDef.class)).collect(Collectors.toList());
    }

    @Override
    public List<FeatureDef> resolve() {
        ensureBuiltIn();
        if (cached != null && System.currentTimeMillis() - loadedAt < TTL_MS) return cached;
        List<FeatureDef> out = builtIn;
        if (configService != null) {
            SysConfig c = configService.getByKey(KEY);
            if (c != null && c.getConfigValue() != null && !c.getConfigValue().trim().isEmpty()) {
                try {
                    Map<?,?> root = OM.readValue(c.getConfigValue(), Map.class);
                    List<FeatureDef> fromCfg = parse((List<?>) root.get("features"));
                    if (!fromCfg.isEmpty()) out = fromCfg;
                } catch (Exception ignore) { /* 配置坏 → 回退内置，绝不抛 */ }
            }
        }
        cached = out; loadedAt = System.currentTimeMillis();
        return out;
    }

    /** 展平含 children 的全部定义（用于默认集/授权清单） */
    private List<FeatureDef> flat() {
        List<FeatureDef> all = new ArrayList<>();
        for (FeatureDef f : resolve()) {
            all.add(f);
            if (f.getChildren() != null) all.addAll(f.getChildren());
        }
        return all;
    }

    @Override
    public Set<String> defaults(String role) {
        Set<String> out = new HashSet<>();
        for (FeatureDef f : flat()) {
            if (f.getPermKey() == null || f.getDefaults() == null) continue;
            if (Boolean.TRUE.equals(f.getDefaults().get(role))) out.add(f.getPermKey());
        }
        return out;
    }

    @Override
    public Set<String> grantablePerms() {
        Set<String> out = new HashSet<>();
        for (FeatureDef f : flat()) {
            Map<String,Boolean> d = f.getDefaults();
            if (f.getPermKey() == null || d == null) continue;
            // 可开通 = perm 门且并非所有角色都默认授予（如 dev.entry 对 user 默认关闭）
            boolean allGranted = d.values().stream().allMatch(Boolean::booleanValue);
            if ("perm".equals(f.getGate()) && !allGranted) out.add(f.getPermKey());
        }
        return out;
    }

    @Override
    public FeatureDef featureOfPath(String path) {
        FeatureDef best = null; int bestLen = -1;
        for (FeatureDef f : resolve()) {
            String p = f.getApi();
            if (p != null && !p.isEmpty() && path.startsWith(p) && p.length() > bestLen) {
                best = f; bestLen = p.length();
            }
        }
        return best; // 最长前缀优先
    }

    @Override
    public void evict() { cached = null; }
}
