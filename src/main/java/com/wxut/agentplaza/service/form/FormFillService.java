package com.wxut.agentplaza.service.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.entity.FormRecord;
import com.wxut.agentplaza.mapper.FormRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能填报-填报流水线。
 * 流程：解析模板 → 检索数仓 → 逐字段推送（db 直填 / ai 流式起草 / need 待补充）→ 落库。
 * SSE 事件：{type: stage|field|nstart|delta|nend|done|error, ...}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormFillService {

    private final FormWarehouseService warehouse;
    private final FormLlmService llm;
    private final FormRecordMapper recordMapper;
    private final ObjectMapper om = new ObjectMapper();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "form-fill");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter stream(Long recordId) {
        FormRecord record = recordMapper.selectById(recordId);
        if (record == null) throw new IllegalArgumentException("填报记录不存在");
        SseEmitter emitter = new SseEmitter(300_000L);
        pool.submit(() -> {
            SafeEmitter se = new SafeEmitter(emitter);
            try {
                run(record, se);
            } catch (Exception e) {
                log.error("智能填报：流水线异常 recordId={}", recordId, e);
                se.send(evt("error", "message", "填报流水线异常：" + e.getMessage()));
                se.complete();
            }
        });
        return emitter;
    }

    /** 单字段重新生成（叙述字段） */
    public SseEmitter regen(Long recordId, int index) {
        FormRecord record = recordMapper.selectById(recordId);
        if (record == null) throw new IllegalArgumentException("填报记录不存在");
        SseEmitter emitter = new SseEmitter(180_000L);
        pool.submit(() -> {
            SafeEmitter se = new SafeEmitter(emitter);
            try {
                List<Map<String, Object>> fields = readFields(record);
                if (index < 0 || index >= fields.size()) throw new IllegalArgumentException("字段序号越界");
                Map<String, Object> f = fields.get(index);
                Map<String, Object> profile = warehouse.getProfile(record.getTeacherId(), record.getTeacherName());
                String khnf = fields.stream().filter(x -> "_khnf".equals(str(x.get("key"))))
                        .map(x -> str(x.get("val"))).findFirst().orElse(assessYear());
                se.send(evt("nstart", "index", index, "label", f.get("label")));
                String text = narrative(se, index, f, profile, record, khnf);
                f.put("val", text);
                record.setFieldsJson(om.writeValueAsString(fields));
                record.setFillCount(countFilled(fields));
                recordMapper.updateById(record);
                se.send(evt("done", "index", index));
                se.complete();
            } catch (Exception e) {
                se.send(evt("error", "message", e.getMessage()));
                se.complete();
            }
        });
        return emitter;
    }

    /**
     * SSE 连接死亡（前置代理掐长连接/浏览器断开/emitter 超时）后静默停止推送，
     * 流水线继续跑完并落库——服务端结果不依赖浏览器连接存活。
     */
    private class SafeEmitter {
        private final SseEmitter target;
        private volatile boolean dead = false;
        SafeEmitter(SseEmitter target) { this.target = target; }
        void send(Map<String, Object> data) {
            if (dead) return;
            try {
                target.send(SseEmitter.event().data(om.writeValueAsString(data)));
            } catch (Exception e) {
                dead = true;
            }
        }
        void complete() {
            if (dead) return;
            try { target.complete(); } catch (Exception ignore) { }
        }
    }

    // ================= 主流程 =================

    private void run(FormRecord record, SafeEmitter emitter) throws Exception {
        List<Map<String, Object>> fields = readFields(record);
        int total = (int) fields.stream().filter(f -> !"_khnf".equals(str(f.get("key")))).count();
        // 计数字段的显示序号：_khnf 为隐藏字段不计入（显示 0，前端不渲染百分比）
        int[] seq = new int[fields.size()];
        {
            int c = 0;
            for (int i = 0; i < fields.size(); i++) {
                seq[i] = "_khnf".equals(str(fields.get(i).get("key"))) ? 0 : ++c;
            }
        }
        emitter.send(evt("start", "total", total, "recordId", record.getId()));

        // 阶段 1：解析模板
        emitter.send(evt("stage", "stage", "parse", "state", "live", "text", "正在识别表格字段与分组…"));
        Thread.sleep(400);
        emitter.send(evt("stage", "stage", "parse", "state", "done", "text",
                "已识别 " + total + " 项字段 · " + groupsOf(fields) + " 个分组"));

        // 阶段 2：检索数仓
        emitter.send(evt("stage", "stage", "fetch", "state", "live", "text", "正在检索教师数据仓…"));
        Map<String, Object> profile = warehouse.getProfile(record.getTeacherId(), record.getTeacherName());
        boolean down = Boolean.TRUE.equals(profile.get("_down"));
        boolean nomatch = Boolean.TRUE.equals(profile.get("_nomatch"));
        boolean fb = down || nomatch;
        emitter.send(evt("stage", "stage", "fetch", "state", "done", "text",
                down ? "教职工数仓暂不可达 · 数仓直填字段本次留空，请在下一步手动补充"
                : nomatch ? "工号 " + record.getTeacherId() + " 未在教职工数仓命中 · 数仓直填字段请手动补充"
                : "已命中 " + profile.get("name") + "（工号 " + profile.get("zgh") + "）· 教职工 / 学历 / 科研 / 评价 / 考核"));

        // 阶段 3：生成内容（先由大模型分析本次填报对应的考核年度）
        emitter.send(evt("stage", "stage", "generate", "state", "live", "text", "大模型分析考核年度…"));
        String khnf = assessYear();
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            if ("_khnf".equals(str(f.get("key")))) {
                f.put("val", khnf);
                emitter.send(fieldEvent(i, f, false, seq[i], total));
            }
        }
        emitter.send(evt("stage", "stage", "generate", "state", "live", "text",
                "考核年度：" + khnf + " · 开始直填结构化字段…"));
        int filled = 0;
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            String src = str(f.get("src"));
            boolean edited = Boolean.TRUE.equals(f.get("edited"));
            if ("db".equals(src)) {
                // 用户已手动编辑过的字段不覆盖，保留本人填写
                if (!edited) {
                    String val = str(profile.get(str(f.get("key"))));
                    f.put("val", val);
                }
                if (!str(f.get("val")).isEmpty()) filled++;
                emitter.send(fieldEvent(i, f, fb, seq[i], total));
                Thread.sleep(60);
            } else if ("material".equals(src)) {
                // 素材为用户已输入内容，只回显与计数，不参与直填/生成
                if (!str(f.get("val")).isEmpty()) filled++;
                emitter.send(fieldEvent(i, f, fb, seq[i], total));
            } else if ("need".equals(src)) {
                if (!str(f.get("val")).isEmpty()) filled++;
                emitter.send(fieldEvent(i, f, fb, seq[i], total));
            }
        }
        emitter.send(evt("stage", "stage", "generate", "state", "live", "text", "大模型起草叙述性内容…"));
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            if (!"ai".equals(str(f.get("src")))) continue;
            // 用户已手动改写过的叙述字段（如本人总结）不再覆盖
            if (Boolean.TRUE.equals(f.get("edited")) && !str(f.get("val")).isEmpty()) {
                emitter.send(evt("nstart", "index", i, "label", f.get("label")));
                emitter.send(evt("nend", "index", i, "val", str(f.get("val"))));
                filled++;
                continue;
            }
            emitter.send(evt("nstart", "index", i, "label", f.get("label")));
            String text = narrative(emitter, i, f, profile, record, khnf);
            f.put("val", text);
            filled++;
            emitter.send(evt("nend", "index", i, "val", text));
        }
        emitter.send(evt("stage", "stage", "generate", "state", "done", "text", "填写完成 · 叙述内容已标记待核对"));

        // 落库
        String status = "done";
        for (Map<String, Object> f : fields) {
            if ("need".equals(str(f.get("src"))) && str(f.get("val")).isEmpty()) status = "todo";
        }
        record.setFieldsJson(om.writeValueAsString(fields));
        record.setFillCount(filled);
        record.setTotalCount(total);
        record.setStatus(status);
        recordMapper.updateById(record);

        emitter.send(evt("done", "fillCount", filled, "total", total, "status", status));
        emitter.complete();
    }

    /** 考核年度：大模型按当前日期与填报场景分析；失败回落「上一个自然年」规则 */
    public String assessYear() {
        String today = java.time.LocalDate.now().toString();
        String fallback = String.valueOf(java.time.LocalDate.now().getYear() - 1);
        try {
            String sys = "你负责判断事业单位年度考核登记表对应的考核年度。"
                    + "规则常识：年度考核在次年初进行；若当前已接近年末（11-12月），也可能在为本年度考核做准备。"
                    + "只输出一个四位年份数字，不要任何其他文字。";
            String user = "今天是 " + today + "。教师正在填写《江苏省事业单位工作人员年度考核登记表》。"
                    + "请判断本次填报针对的考核年度，只输出年份数字。";
            String out = llm.chat(sys, user).replaceAll("[^0-9]", "");
            if (out.length() == 4) {
                int y = Integer.parseInt(out);
                int now = java.time.LocalDate.now().getYear();
                if (y >= now - 1 && y <= now) return out;
            }
        } catch (Exception e) {
            log.warn("智能填报：考核年度大模型分析失败，回落规则推断：{}", e.getMessage());
        }
        return fallback;
    }

    /** 素材 AI 填写：按维度结合数仓事实生成要点（每行一条），仅供预填，不直接覆盖用户内容 */
    public String aiMaterial(String teacherId, String teacherName, String fieldKey, String fieldLabel, String current) {
        Map<String, Object> profile = warehouse.getProfile(teacherId, teacherName);
        String khnf = assessYear();
        String dim;
        switch (fieldKey == null ? "" : fieldKey) {
            case "matJi": dim = "绩·本年度工作要点：围绕教学、科研（论文/专利/项目/经费）、竞赛指导、建设任务等归纳实绩要点，每条尽量带数仓中的具体数字。"; break;
            case "matDe": dim = "德·思想政治与师德师风：理论学习、师德建设、立德树人方面要点；数仓无直接数据的部分给通用条目，不编造具体事实。"; break;
            case "matNeng": dim = "能·业务能力提升：培训进修、技能取证、企业实践、信息化能力等要点；数仓无直接数据的部分给通用条目，不编造。"; break;
            case "matQin": dim = "勤·工作态度与出勤：出勤守岗、承担公共事务、社会兼职等要点。"; break;
            case "matLian": dim = "廉·廉洁自律：纪律学习、廉洁承诺、规范履职等要点。"; break;
            default: dim = "补充素材：与年度考核相关的其他事实要点。"; break;
        }
        String sys = "你是高校教师年度考核素材整理助手。依据数仓事实生成填写要点，每行一条、简洁具体；"
                + "数仓没有的事实不要编造，可以给出该维度的通用要点（不带具体数字）。只输出要点列表，不要标题和说明。";
        String user = "考核年度：" + khnf + "。栏目：" + fieldLabel + "。" + dim
                + "\n教师数仓事实（JSON）：" + jsonOf(profile)
                + (current == null || current.trim().isEmpty() ? "" : "\n\n教师已写的要点（参考语气与侧重，可补充但不要重复）：\n" + current.trim())
                + "\n\n输出 3-6 条要点，每行一条。";
        try {
            String text = llm.chat(sys, user).trim();
            if (!text.isEmpty()) return text;
        } catch (Exception e) {
            log.warn("智能填报：素材AI填写失败（{}）：{}", fieldKey, e.getMessage());
            throw new IllegalArgumentException("AI 起草失败，请稍后重试或手动填写");
        }
        throw new IllegalArgumentException("AI 起草失败，请手动填写");
    }

    /** 叙述字段：优先 LLM 流式起草；失败回落事实拼装 */
    private String narrative(SafeEmitter emitter, int index, Map<String, Object> f,
                             Map<String, Object> profile, FormRecord record, String khnf) {
        String key = str(f.get("key"));
        String label = str(f.get("label"));
        String brief = FormFieldCatalog.narrativeBrief(key);
        boolean isSummary = "summary1".equals(key);
        String sys = "你是高校教师填报材料撰写助手。只依据用户提供的事实数据与素材撰写，禁止编造任何数字、荣誉或经历；"
                + "事实数据中没有的信息直接略过，不要用“某”“若干”等占位。语言平实书面化。直接输出正文，不要输出任何前缀、标题或说明。";
        String user = "本次填报的考核年度：" + khnf + "年（行文提及年度时以此为准）。栏目：" + label + "。写作要求：" + brief + "。"
                + "教师事实数据（JSON）：" + jsonOf(profile)
                + (isSummary ? "\n\n教师提供的分节素材要点（优先使用，逐条落实；未提供的部分用通用表述带过）：\n" + materialsOf(record) : "");
        StringBuilder sb = new StringBuilder();
        try {
            String full = llm.chatStream(sys, user, piece -> {
                sb.append(piece);
                emitter.send(evt("delta", "index", index, "chunk", piece));
            });
            return full;
        } catch (Exception e) {
            log.warn("智能填报：LLM 起草失败（{}），回落事实拼装：{}", label, e.getMessage());
            String text = isSummary ? fallbackSummary(profile, record, khnf) : fallbackNarrative(key, profile);
            // 回落也按块推送，前端体验一致
            for (String piece : chunk(text, 6)) {
                emitter.send(evt("delta", "index", index, "chunk", piece));
            }
            return text;
        }
    }

    /** 从记录 fieldsJson 收集 material 素材，拼为分节素材块 */
    private String materialsOf(FormRecord record) {
        try {
            List<Map<String, Object>> fields = om.readValue(record.getFieldsJson(), List.class);
            StringBuilder b = new StringBuilder();
            for (Map<String, Object> f : fields) {
                if (!"material".equals(str(f.get("src")))) continue;
                String label = str(f.get("label"));
                String val = str(f.get("val"));
                if (val.isEmpty()) continue;
                b.append("【").append(label).append("】\n").append(val).append("\n");
            }
            return b.length() == 0 ? "（教师未提供素材，全部用通用表述带过，不得编造具体事实）" : b.toString();
        } catch (Exception e) {
            return "（教师未提供素材）";
        }
    }

    /** 总结兜底：无 LLM 时按素材逐条拼装德能勤绩廉骨架 */
    private String fallbackSummary(Map<String, Object> p, FormRecord record, String khnf) {
        String name = str(p.get("name")), zc = str(p.get("zc")), xl = str(p.get("xl"));
        StringBuilder b = new StringBuilder(khnf + " 年度，本人立足本职岗位，认真完成各项工作任务，现总结如下：\n");
        Map<String, String> mats = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> fields = om.readValue(record.getFieldsJson(), List.class);
            for (Map<String, Object> f : fields) {
                if ("material".equals(str(f.get("src"))) && !str(f.get("val")).isEmpty()) {
                    mats.put(str(f.get("label")), str(f.get("val")));
                }
            }
        } catch (Exception ignore) {
        }
        b.append("德：").append(mats.entrySet().stream().filter(e -> e.getKey().startsWith("德"))
                .map(Map.Entry::getValue).findFirst().orElse("坚持政治学习，遵守师德师风各项要求。")).append("\n");
        b.append("能：").append(mats.entrySet().stream().filter(e -> e.getKey().startsWith("能"))
                .map(Map.Entry::getValue).findFirst().orElse("注重业务能力提升，参加培训学习。")).append("\n");
        b.append("勤：").append(mats.entrySet().stream().filter(e -> e.getKey().startsWith("勤"))
                .map(Map.Entry::getValue).findFirst().orElse("全年出勤正常，工作认真负责。")).append("\n");
        StringBuilder ji = new StringBuilder();
        mats.entrySet().stream().filter(e -> e.getKey().startsWith("绩")).map(Map.Entry::getValue)
                .findFirst().ifPresent(v -> ji.append(v.replace("\n", "；")));
        b.append("绩：").append(ji.length() > 0 ? ji : "完成年度各项教学与科研工作任务。").append("\n");
        b.append("廉：").append(mats.entrySet().stream().filter(e -> e.getKey().startsWith("廉"))
                .map(Map.Entry::getValue).findFirst().orElse("严格遵守廉洁自律各项规定。")).append("\n");
        b.append("（以上为兜底草稿，仅基于教师素材与基础信息").append(xl.isEmpty() ? "" : "，" + zc + name + "，" + xl).append("，建议连接大模型后重新起草。）");
        return b.toString();
    }

    /** 事实拼装兜底（LLM 不可用时） */
    private String fallbackNarrative(String key, Map<String, Object> p) {
        String name = str(p.get("name")), zc = str(p.get("zc")), xl = str(p.get("xl"));
        String zy = str(p.get("zy")), since = str(p.get("since"));
        String lwF = str(p.get("lwF")), zlF = str(p.get("zlF")), hxF = str(p.get("hxF"));
        String jf = str(p.get("jf")), pjS = str(p.get("pjS")), pjD = str(p.get("pjD")), pjT = str(p.get("pjT"));
        String zxJ = str(p.get("zxJ"));
        switch (key == null ? "" : key) {
            case "narr1": {
                StringBuilder b = new StringBuilder("本人" + nz(zc, "教师") + nz(name, "") + "，" + since + "参加工作，"
                        + nz(xl, "") + nz(zy, "专业") + "毕业。");
                if (!pjS.isEmpty()) b.append("近三年学生评教均分 ").append(pjS).append(" 分。");
                if (!lwF.isEmpty()) b.append("科研方面以第一作者发表论文 ").append(lwF).append(" 篇");
                if (!zlF.isEmpty()) b.append("，专利第一发明人 ").append(zlF).append(" 项");
                if (!hxF.isEmpty()) b.append("，主持横向课题 ").append(hxF).append(" 项");
                if (!jf.isEmpty()) b.append("，到账经费 ").append(jf).append(" 万元");
                if (!lwF.isEmpty()) b.append("。");
                return b.toString();
            }
            case "narr2": {
                StringBuilder b = new StringBuilder("申报人围绕" + nz(zy, "本专业") + "方向持续开展工作，");
                if (!lwF.isEmpty()) b.append("以第一作者发表论文 ").append(lwF).append(" 篇");
                if (!zlF.isEmpty()) b.append("、专利 ").append(zlF).append(" 项");
                b.append("，业绩材料完整、证据链清晰。未来三年计划在既有方向上深化积累，推进校企协同与成果转化，补强主持类项目。");
                return b.toString();
            }
            case "res1": {
                StringBuilder b = new StringBuilder();
                if (!lwF.isEmpty()) b.append("论文以第一作者为主（").append(lwF).append(" 篇）；");
                if (!zlF.isEmpty()) b.append("专利第一发明人 ").append(zlF).append(" 项；");
                if (!hxF.isEmpty()) b.append("横向到账 ").append(jf).append(" 万元；");
                if (!zxJ.isEmpty()) b.append("纵向项目以参与为主（").append(zxJ).append(" 项）。");
                b.append("整体结构均衡，建议突出主导课题以补强。");
                return b.toString();
            }
            case "res2": {
                StringBuilder b = new StringBuilder();
                if (!pjS.isEmpty()) b.append("学生评教 ").append(pjS);
                if (!pjD.isEmpty()) b.append("、督导 ").append(pjD);
                if (!pjT.isEmpty()) b.append("、同行 ").append(pjT);
                if (b.length() > 0) b.append("，三类评价一致性良好；");
                b.append("主讲课程覆盖本专业核心链条，教学运行稳定。");
                return b.toString();
            }
            case "lws1": case "lws2": case "lws3":
                return str(p.get(key));
            default:
                return str(p.get(key));
        }
    }

    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    /** seq=计数字段的显示序号（_khnf 为 0，前端不渲染 x/y 与百分比）；total=过滤 _khnf 后的总数 */
    private Map<String, Object> fieldEvent(int index, Map<String, Object> f, boolean fallback, int seq, int total) {
        String basis = str(f.get("basis"));
        if (fallback && !"need".equals(str(f.get("src"))) && !basis.isEmpty()) {
            basis += "（数仓本次未提供，请人工核对）";
        }
        Map<String, Object> e = evt("field", "index", index, "idx", seq, "total", total);
        e.put("label", f.get("label"));
        e.put("g", f.get("g"));
        e.put("src", f.get("src"));
        e.put("key", f.get("key"));
        e.put("conf", f.get("conf"));
        e.put("hint", f.get("hint"));
        e.put("val", f.get("val"));
        e.put("basis", basis);
        return e;
    }

    private static Map<String, Object> evt(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readFields(FormRecord record) throws Exception {
        List<Map<String, Object>> fields = om.readValue(record.getFieldsJson(), List.class);
        // 保证 val 键存在
        for (Map<String, Object> f : fields) f.putIfAbsent("val", "");
        return fields;
    }

    private static int countFilled(List<Map<String, Object>> fields) {
        int n = 0;
        for (Map<String, Object> f : fields) {
            if (!str(f.get("val")).isEmpty()) n++;
        }
        return n;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String nz(String v, String dft) {
        return v == null || v.isEmpty() ? dft : v;
    }

    private String jsonOf(Map<String, Object> profile) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>(profile);
            safe.remove("_fallback");
            return om.writeValueAsString(safe);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static int groupsOf(List<Map<String, Object>> fields) {
        java.util.Set<String> g = new java.util.HashSet<>();
        for (Map<String, Object> f : fields) g.add(str(f.get("g")));
        return g.size();
    }
}
