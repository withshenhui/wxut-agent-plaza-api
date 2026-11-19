package com.wxut.agentplaza.service.form;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能填报-规范字段目录。
 * 所有预置模板由本目录组装；自定义模板经 LLM 映射到本目录的 key 上。
 * 字段口径与 mcp-wxut-main 教师线工具（usr_gxai 数仓）保持一致。
 */
public final class FormFieldCatalog {

    private FormFieldCatalog() {}

    /** 字段定义：g=分组 label=展示名 src=db/ai/need key=数仓取值键 */
    public static class Def {
        public final String g, label, src, key, basis, hint;
        public final double conf;
        public Def(String g, String label, String src, String key, double conf, String basis, String hint) {
            this.g = g; this.label = label; this.src = src; this.key = key;
            this.conf = conf; this.basis = basis; this.hint = hint;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("g", g); m.put("label", label); m.put("src", src); m.put("key", key);
            m.put("conf", conf); m.put("basis", basis); m.put("hint", hint == null ? "" : hint);
            return m;
        }
    }

    private static final String JZG = "教职工主表（数仓）· 工号精确匹配";
    private static final String XL = "学历学位表（数仓）· 取最高学历记录";
    private static final String LW = "科研系统 · 论文表（数仓）· 第一作者工号口径";
    private static final String ZL = "科研系统 · 专利表（数仓）· 第一发明人 + 成员双口径";
    private static final String HX = "科研系统 · 横向项目表（数仓）· 负责人姓名 + 成员表";
    private static final String ZX = "科研系统 · 纵向项目表（数仓）· 参与人员工号";
    private static final String JF = "科研系统 · 经费到账表（数仓）· 按项目关联汇总";
    private static final String PJ = "教学评价表（数仓）· 近三年平均";
    private static final String KH = "年度考核表（数仓）· 按年度取最近记录";
    private static final String MZ = "教职工主表（数仓）· 民族代码 01，字典待校准";

    private static Def F(String g, String label, String src, String key, double conf, String basis, String hint) {
        return new Def(g, label, src, key, conf, basis, hint);
    }

    private static final List<Def> G1 = Arrays.asList(
            F("g1", "姓名", "db", "name", .99, JZG, null),
            F("g1", "工号", "db", "zgh", .99, JZG, null),
            F("g1", "性别", "db", "gender", .99, JZG, null),
            F("g1", "出生年月", "db", "birth", .95, JZG, null),
            F("g1", "民族", "db", "mz", .62, MZ, null),
            F("g1", "籍贯", "need", "", 0, null, "数仓未采集，请手动填写"),
            F("g1", "政治面貌", "need", "", 0, null, "数仓未采集，请手动填写"),
            F("g1", "现任职称", "db", "zc", .9, JZG, null),
            F("g1", "参加工作时间", "db", "since", .95, JZG, null),
            F("g1", "现任职务", "need", "cur", 0, null, "数仓未采集，请手动填写"),
            F("g1", "联系电话", "need", "", 0, null, "隐私字段不返回数仓，请手动填写"),
            F("g1", "电子邮箱", "need", "", 0, null, "隐私字段不返回数仓，请手动填写"));

    private static final List<Def> G2 = Arrays.asList(
            F("g2", "最高学历", "db", "xl", .96, XL, null),
            F("g2", "最高学位", "db", "xw", .96, XL, null),
            F("g2", "毕业院校", "db", "xx", .93, XL, null),
            F("g2", "所学专业", "db", "zy", .93, XL, null),
            F("g2", "学位授予时间", "db", "xwTime", .9, XL, null));

    private static final List<Def> G3 = Arrays.asList(
            F("g3", "论文数（第一作者）", "db", "lwF", .98, LW, null),
            F("g3", "论文数（合计）", "db", "lwA", .85, LW, null),
            F("g3", "专利数（第一发明人）", "db", "zlF", .8, ZL, null),
            F("g3", "专利数（参与）", "db", "zlJ", .6, ZL, null),
            F("g3", "横向项目（负责）", "db", "hxF", .82, HX, null),
            F("g3", "横向项目（参与）", "db", "hxJ", .62, HX, null),
            F("g3", "纵向项目（参与）", "db", "zxJ", .85, ZX, null),
            F("g3", "科研经费到账（万元）", "db", "jf", .9, JF, null),
            F("g3", "代表作论文 一", "ai", "lws1", .9, "大模型基于论文表（数仓）检索结果整理 · 可重新生成或修改", null),
            F("g3", "代表作论文 二", "ai", "lws2", .9, "大模型基于论文表（数仓）检索结果整理", null),
            F("g3", "代表作论文 三", "ai", "lws3", .88, "大模型基于论文表（数仓）检索结果整理", null),
            F("g3", "科研业绩综述", "ai", "res1", .87, "大模型综合论文 / 专利 / 项目 / 经费数据起草", null));

    private static final List<Def> G4 = Arrays.asList(
            F("g4", "近三年学生评教均分", "db", "pjS", .96, PJ, null),
            F("g4", "近三年督导评价均分", "db", "pjD", .96, PJ, null),
            F("g4", "近三年同行评价均分", "db", "pjT", .96, PJ, null),
            F("g4", "年均教学工作量（学时）", "need", "", 0, null, "课表系统数据接入中，请手动填写"),
            F("g4", "主持教改项目数", "db", "jg", .9, "教研系统（数仓）· 主持人工号口径", null),
            F("g4", "教学效果综述", "ai", "res2", .88, "大模型基于三类评价数据起草", null));

    private static final List<Def> G5 = Arrays.asList(
            F("g5", "年度考核（2024）", "db", "kh2024", .9, KH, null),
            F("g5", "年度考核（2023）", "db", "kh2023", .9, KH, null),
            F("g5", "年度考核（2022）", "db", "kh2022", .9, KH, null),
            F("g5", "荣誉称号", "need", "", 0, null, "荣誉库接入中，请手动填写获奖名称与年度"));

    private static final List<Def> G6 = Arrays.asList(
            F("g6", "个人述职概要", "ai", "narr1", .86, "大模型基于数仓事实起草 · 请核对后修改", null),
            F("g6", "申报理由与未来规划", "ai", "narr2", .84, "大模型基于数仓事实起草 · 请核对后修改", null));

    /** 全量目录（职称评审业绩表 = 41 项） */
    public static List<Def> full() {
        List<Def> all = new ArrayList<>();
        all.addAll(G1); all.addAll(G2); all.addAll(G3); all.addAll(G4); all.addAll(G5); all.addAll(G6);
        return all;
    }

    private static List<Map<String, Object>> defs(List<Def>... groups) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Def> g : groups) for (Def d : g) out.add(d.toMap());
        return out;
    }

    private static List<Map<String, Object>> defsExtra(List<Def> group, String g, Object[][] extra) {
        List<Map<String, Object>> out = defs(group);
        for (Object[] e : extra) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("g", g); m.put("label", (String) e[0]); m.put("src", "need"); m.put("key", "");
            m.put("conf", 0.0); m.put("basis", ""); m.put("hint", (String) e[1]);
            out.add(m);
        }
        return out;
    }

    /** 预置模板定义（与原型口径一致） */
    public static List<Object[]> presets() {
        List<Object[]> out = new ArrayList<>();
        // 1) 职称评审业绩表 = 全量 41 项
        out.add(new Object[]{"职称评审业绩表", "副教授 / 教授申报 · 41 项字段",
                "覆盖基本信息、学历学位、科研业绩、教学评价与个人陈述，适配校人事处评审表版式。", defs(G1, G2, G3, G4, G5, G6)});
        // 2) 教师基本信息采集表 = G1(12)+G2(5)+11 项补充 = 28
        out.add(new Object[]{"教师基本信息采集表", "年度信息核对 · 28 项字段",
                "人事处年度信息核对口径，含联系方式、家庭等需人工确认项。",
                defsExtra(concat(G1, G2), "g1", new Object[][]{
                        {"婚姻状况", "数仓未采集，请手动填写"}, {"配偶姓名", "数仓未采集，请手动填写"},
                        {"配偶工作单位", "数仓未采集，请手动填写"}, {"家庭住址", "数仓未采集，请手动填写"},
                        {"紧急联系人", "数仓未采集，请手动填写"}, {"办公地点", "数仓未采集，请手动填写"},
                        {"外语水平", "数仓未采集，请手动填写"}, {"计算机水平", "数仓未采集，请手动填写"},
                        {"健康状况", "数仓未采集，请手动填写"}, {"兴趣特长", "数仓未采集，请手动填写"},
                        {"电子照片", "上传后随导出文件一并生成"}})});
        // 3) 年度考核登记表 = G1(12)+G2(5)+G4(6)+G5(4)+6 项补充 = 33
        out.add(new Object[]{"年度考核登记表", "事业单位工作人员年度考核 · 33 项字段",
                "考核表人事口径，自动带入近三年考核结果与述职概要。",
                defsExtra(concat(G1, concat(G2, concat(G4, G5))), "g5", new Object[][]{
                        {"本年度出勤情况", "考勤系统数据接入中，请手动填写"}, {"奖惩情况", "请手动填写"},
                        {"培训学时", "培训系统数据接入中，请手动填写"}, {"社会兼职", "请手动填写"},
                        {"廉洁自律情况", "请手动填写"}, {"下一步改进计划", "可参考 AI 起草内容后修改"}})});
        // 4) 科研业绩统计表 = G2(5)+G3(12)+评教(1)+考核(3)+述职(1)+4 项补充 = 26
        out.add(new Object[]{"科研业绩统计表", "学院科研秘书汇总 · 26 项字段",
                "按论文、专利、项目、经费四大类汇总，支持批量生成。",
                defsExtra(concat(G2, concat(G3, Arrays.asList(G4.get(0)),
                        Arrays.asList(G5.get(0), G5.get(1), G5.get(2)), G6.subList(0, 1))), "g3", new Object[][]{
                        {"科研平台参与情况", "请手动填写"}, {"团队建设情况", "请手动填写"},
                        {"科研获奖", "荣誉库接入中，请手动填写"}, {"学术兼职", "请手动填写"}})});
        return out;
    }

    @SafeVarargs
    private static List<Def> concat(List<Def>... lists) {
        List<Def> out = new ArrayList<>();
        for (List<Def> l : lists) out.addAll(l);
        return out;
    }

    public static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("g1", "基本信息"); GROUPS.put("g2", "学历学位"); GROUPS.put("g3", "科研业绩");
        GROUPS.put("g4", "教学评价"); GROUPS.put("g5", "考核与荣誉"); GROUPS.put("g6", "个人陈述");
    }

    /** 叙述字段的生成说明（key → 生成要求），供 LLM 与兜底文案使用 */
    public static String narrativeBrief(String key) {
        switch (key == null ? "" : key) {
            case "narr1": return "个人述职概要，150-250字，涵盖教学、科研、管理工作，只使用给定事实数据";
            case "narr2": return "申报理由与未来三年规划，120-200字，围绕业绩证据链展开";
            case "res1": return "科研业绩综述，100-180字，突出结构均衡性与经费规模";
            case "res2": return "教学效果综述，100-180字，围绕三类评价一致性与工作量";
            case "lws1": case "lws2": case "lws3": return "将论文题名、期刊、年份整理为一条规范引用格式";
            default: return "依据给定事实数据撰写该栏目内容，120-200字";
        }
    }
}
