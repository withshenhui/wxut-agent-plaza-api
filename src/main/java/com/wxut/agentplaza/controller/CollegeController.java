package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysCollege;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "学院")
@RestController
@RequestMapping("/api/v1/colleges")
@RequiredArgsConstructor
public class CollegeController {

    private final SysCollegeMapper collegeMapper;

    /** 公开：学院列表（含全校/机构虚拟行，管理端与前端展示用） */
    @GetMapping
    @Operation(summary = "学院列表")
    public Result<List<Map<String, Object>>> list() {
        List<SysCollege> all = collegeMapper.selectList(
                new LambdaQueryWrapper<SysCollege>().orderByAsc(SysCollege::getSort));
        List<Map<String, Object>> out = all.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("scopeAll", c.getScopeAll());
            return m;
        }).collect(Collectors.toList());
        return Result.success(out);
    }
}
