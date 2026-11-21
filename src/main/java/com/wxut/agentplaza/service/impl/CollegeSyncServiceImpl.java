package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.entity.SysCollege;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.entity.SysUserPerm;
import com.wxut.agentplaza.mapper.SysCollegeMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.mapper.SysUserPermMapper;
import com.wxut.agentplaza.service.CollegeSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollegeSyncServiceImpl implements CollegeSyncService {

    private final SysUserMapper userMapper;
    private final SysCollegeMapper collegeMapper;
    private final SysUserPermMapper permMapper;
    private final com.wxut.agentplaza.config.WarehouseJdbcProvider warehouseProvider;

    /** 数仓单位名 → sys_college 名（归一化） */
    private static final Map<String, String> NAME_FIX = new HashMap<>();
    static {
        NAME_FIX.put("机械技术学院", "机械工程学院");
        NAME_FIX.put("控制技术学院", "控制工程学院");
        NAME_FIX.put("汽车与交通学院", "汽车与交通工程学院");
        NAME_FIX.put("物联网技术学院", "物联网工程学院");
        NAME_FIX.put("外语与旅游学院", "文旅学院");
        NAME_FIX.put("国教院", "国际教育学院");
        NAME_FIX.put("开源创新创业学院（应用技术学院）", "开源创新创业学院");
        NAME_FIX.put("开源创新创业学院、智能制造技术研究院", "开源创新创业学院");
        NAME_FIX.put("双创学院", "开源创新创业学院");
    }

    /** 已解部门 GUID → 学院名（2026-11-21 由评教/督导锚定反推，80% 一致性阈值） */
    private static final Map<String, String> BMDM_DICT = new HashMap<>();
    static {
        BMDM_DICT.put("DB5ECB12B57F40AEBF98573ACF76DF8F", "__INFO_CENTER__");
        BMDM_DICT.put("5F5FD3C558E64F988E81274A712AF2F8", "开源创新创业学院");
        BMDM_DICT.put("F724C9724C114B5B861024156CD24D6C", "物联网工程学院");
        BMDM_DICT.put("E1420D9DEA994A9C937E39E1D2112428", "机械工程学院");
        BMDM_DICT.put("D31E6A6CDD5349E29045F6B9145442FE", "控制工程学院");
        BMDM_DICT.put("CFF12104A4D042D88886CD959981EB40", "汽车与交通工程学院");
        BMDM_DICT.put("EB0CC75B6CDC4A7999EE52D89FF314E8", "机械工程学院");
        BMDM_DICT.put("F248FE5D02A54FD3AA153751D39F2C0D", "财经学院");
        BMDM_DICT.put("DDE5AD7EE8B447E2ADC78BE78D9B3507", "设计艺术学院");
        BMDM_DICT.put("9B1FC2ED865847E89041792B93F970C0", "管理学院");
        BMDM_DICT.put("F191DF2F3B7A4C2ABD0CD24CD12397DE", "集成电路学院");
        BMDM_DICT.put("A63BCD08F92B483DBC9F671ED544BC97", "设计艺术学院");
        BMDM_DICT.put("3CEE2DB3ED5E48FFAE010D098C26F35D", "管理学院");
        BMDM_DICT.put("B128BB910D144939BD5E8AED5A878FE7", "文旅学院");
        BMDM_DICT.put("B35AAD294EB247E99035CE34EA057748", "文旅学院");
        BMDM_DICT.put("DDCE6FCAF1C64249895A94002EE0134D", "机械工程学院");
        BMDM_DICT.put("CB9A1A0489A84275A364DC2D6F69E996", "财经学院");
        BMDM_DICT.put("FD7096EFD9B244589988BDE1AEFC53A7", "机械工程学院");
        BMDM_DICT.put("5F5FD3C558E64F988E81274A712AF2F8", "开源创新创业学院");
        BMDM_DICT.put("A164AD8F30B34587BB33EA47E58D86CF", "财经学院");
        BMDM_DICT.put("07F2E9CC948945C18C0181567DF93EDF", "汽车与交通工程学院");
        BMDM_DICT.put("B299D61A3A304E798C3B183AB985EA43", "财经学院");
        BMDM_DICT.put("B4C1577EF42E4EBEAB8B1BB4C3EFC32D", "机械工程学院");
        BMDM_DICT.put("945A1EB24EE242C8BB6FE0EC49EA6272", "物联网工程学院");
        BMDM_DICT.put("2809A150D24C483E835166DC9DFDF582", "管理学院");
        BMDM_DICT.put("C1D2AAAA82C54A0BAD88EABD04D690D5", "国际教育学院");
        BMDM_DICT.put("99B97FA5163145558FD9296D94FE1524", "管理学院");
        BMDM_DICT.put("1520863B8D7F45ABBAD13A5B0648882F", "管理学院");
    }

    /** 信息中心部门码 → 自动提平台管理员 */
    private static final String INFO_CENTER_BMDM = "DB5ECB12B57F40AEBF98573ACF76DF8F";

    /** 数仓学院名归一化（公开静态，洞察 detail 等处复用） */
    public static String collegeNameOf(String raw) {
        if (raw == null) return null;
        return NAME_FIX.getOrDefault(raw, raw);
    }

    /** 共享只读数仓池（全后端单池） */
    private JdbcTemplate jt() {
        return warehouseProvider.get();
    }

    @Override
    public SysUser syncIfNeeded(SysUser user, boolean force) {
        boolean synced = user.getCollegeSyncedAt() != null;
        if (!force && (user.getCollegeId() != null || synced)) return user;
        try {
            applyDetection(user);
        } catch (Exception e) {
            log.warn("college sync failed for {}: {}", user.getUsername(), e.getMessage());
        }
        return user;
    }

    @Override
    public String detectCollegeName(String employeeNo) {
        JdbcTemplate jt = jt();
        // 1) 评教主单位
        List<String> units = jt.queryForList(
                "SELECT ksdwmc FROM usr_gxai.t_zkxt_xspj WHERE jgh = ? AND dke_op_type != 'c' AND ksdwmc IS NOT NULL " +
                "GROUP BY ksdwmc ORDER BY COUNT(*) DESC LIMIT 1", String.class, employeeNo);
        String unit = units.isEmpty() ? null : units.get(0);
        // 2) 督导/听课 所属部门
        if (unit == null) {
            units = jt.queryForList(
                    "SELECT jsssbm FROM usr_gxai.t_zkxt_dd WHERE jgh = ? AND dke_op_type != 'c' AND jsssbm IS NOT NULL LIMIT 1",
                    String.class, employeeNo);
            unit = units.isEmpty() ? null : units.get(0);
        }
        if (unit == null) {
            units = jt.queryForList(
                    "SELECT jsssbm FROM usr_gxai.t_zkxt_tx WHERE jgh = ? AND dke_op_type != 'c' AND jsssbm IS NOT NULL LIMIT 1",
                    String.class, employeeNo);
            unit = units.isEmpty() ? null : units.get(0);
        }
        // 3) 部门 GUID 字典
        String bmdm = null;
        List<String> bms = jt.queryForList(
                "SELECT bmdm FROM usr_gxai.t_jzg WHERE zgh = ? AND dke_op_type != 'c' AND bmdm IS NOT NULL LIMIT 1",
                String.class, employeeNo);
        if (!bms.isEmpty()) bmdm = bms.get(0);

        if (bmdm != null && INFO_CENTER_BMDM.equals(bmdm)) return "__INFO_CENTER__";
        if (unit != null) {
            String fixed = NAME_FIX.containsKey(unit) ? NAME_FIX.get(unit) : unit;
            if (isCollegeName(fixed)) return fixed;
        }
        if (bmdm != null && BMDM_DICT.containsKey(bmdm)) {
            return BMDM_DICT.get(bmdm);
        }
        return null;
    }

    /** 推导 + 落库（学院、角色升级、辅导员授权） */
    private void applyDetection(SysUser user) {
        JdbcTemplate jt = jt();
        String detected = detectCollegeName(user.getUsername());
        LocalDateTime now = LocalDateTime.now();

        if ("__INFO_CENTER__".equals(detected)) {
            user.setCollegeId(99);
            user.setCollegeSyncedAt(now);
            if (!"admin".equals(user.getRole())) {
                user.setRole("admin");
                log.info("college sync: {} -> 信息中心, role upgraded to admin", user.getUsername());
            }
            userMapper.updateById(user);
            return;
        }

        Integer cid = null;
        if (detected != null) {
            SysCollege c = collegeMapper.selectOne(new LambdaQueryWrapper<SysCollege>().eq(SysCollege::getName, detected));
            if (c == null) {
                // 非学院机构（马院/体育部/学生处等）
                cid = 99;
            } else {
                cid = c.getId();
            }
        }
        user.setCollegeId(cid);
        user.setCollegeSyncedAt(now);
        userMapper.updateById(user);

        // 辅导员自动授权（幂等）
        grantFdyPerms(user);
        log.info("college sync: {} -> collegeId={} ({})", user.getUsername(), cid, detected);
    }

    private void grantFdyPerms(SysUser user) {
        try {
            JdbcTemplate jt = jt();
            Integer fdy = jt.queryForObject(
                    "SELECT COUNT(*) FROM usr_gxai.t_xg_fdy WHERE fdygh = ? AND dke_op_type != 'c'",
                    Integer.class, user.getUsername());
            if (fdy != null && fdy > 0) {
                for (String perm : PermissionServiceImpl.FDY_GRANTS) {
                    Long exists = permMapper.selectCount(new LambdaQueryWrapper<SysUserPerm>()
                            .eq(SysUserPerm::getUserId, user.getId()).eq(SysUserPerm::getPermKey, perm));
                    if (exists == 0) {
                        SysUserPerm p = new SysUserPerm();
                        p.setUserId(user.getId());
                        p.setPermKey(perm);
                        p.setGranted(1);
                        p.setSource("auto_fdy");
                        permMapper.insert(p);
                    }
                }
                log.info("college sync: {} is fdy, insight perms granted", user.getUsername());
            }
        } catch (Exception e) {
            log.warn("fdy perm grant failed for {}: {}", user.getUsername(), e.getMessage());
        }
    }

    private boolean isCollegeName(String name) {
        return name != null && (name.endsWith("学院") || name.endsWith("大学"));
    }
}
