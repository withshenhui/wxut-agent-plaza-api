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
            try {
                run(record, emitter);
            } catch (Exception e) {
                log.error("智能填报：流水线异常 recordId={}", recordId, e);
                try {
                    send(emitter, evt("error", "message", "填报流水线异常：" + e.getMessage()));
                } catch (Exception ignore) {
                }
                emitter.complete();
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
            try {
                List<Map<String, Object>> fields = readFields(record);
                if (index < 0 || index >= fields.size()) throw new IllegalArgumentException("字段序号越界");
                Map<String, Object> f = fields.get(index);
                Map<String, Object> profile = warehouse.getProfile(record.getTeacherId(), record.getTeacherName());
                send(emitter, evt("nstart", "index", index, "label", f.get("label")));
                String text = narrative(emitter, index, f, profile, record);
                f.put("val", text);
                record.setFieldsJson(om.writeValueAsString(fields));
                record.setFillCount(countFilled(fields));
                recordMapper.updateById(record);
                send(emitter, evt("done", "index", index));
                emitter.complete();
            } catch (Exception e) {
                try {
                    send(emitter, evt("error", "message", e.getMessage()));
                } catch (Exception ignore) {
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    // ================= 主流程 =================

    private void run(FormRecord record, SseEmitter emitter) throws Exception {
        List<Map<String, Object>> fields = readFields(record);
        int total = fields.size();
        send(emitter, evt("start", "total", total, "recordId", record.getId()));

        // 阶段 1：解析模板
        send(emitter, evt("stage", "stage", "parse", "state", "live", "text", "正在识别表格字段与分组…"));
        Thread.sleep(400);
        send(emitter, evt("stage", "stage", "parse", "state", "done", "text",
                "已识别 " + total + " 项字段 · " + groupsOf(fields) + " 个分组"));

        // 阶段 2：检索数仓
        send(emitter, evt("stage", "stage", "fetch", "state", "live", "text", "正在检索教师数据仓…"));
        Map<String, Object> profile = warehouse.getProfile(record.getTeacherId(), record.getTeacherName());
        boolean fb = Boolean.TRUE.equals(profile.get("_fallback"));
        if (fb) {
            record.setTeacherId(str(profile.get("zgh")));
            record.setTeacherName(str(profile.get("name")));
        }
        send(emitter, evt("stage", "stage", "fetch", "state", "done", "text",
                "已命中 " + profile.get("name") + "（工号 " + profile.get("zgh") + "）· 教职工 / 学历 / 科研 / 评价 / 考核"
                        + (fb ? " · 数仓暂不可达，使用本地快照" : "")));

        // 阶段 3：生成内容
        send(emitter, evt("stage", "stage", "generate", "state", "live", "text", "直填结构化字段…"));
        int filled = 0;
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            String src = str(f.get("src"));
            if ("db".equals(src)) {
                String val = str(profile.get(str(f.get("key"))));
                f.put("val", val);
                if (!val.isEmpty()) filled++;
                send(emitter, fieldEvent(i, f, fb));
                Thread.sleep(60);
            } else if ("material".equals(src)) {
                // 素材为用户已输入内容，只回显与计数，不参与直填/生成
                if (!str(f.get("val")).isEmpty()) filled++;
                send(emitter, fieldEvent(i, f, fb));
            } else if ("need".equals(src)) {
                if (!str(f.get("val")).isEmpty()) filled++;
                send(emitter, fieldEvent(i, f, fb));
            }
        }
        send(emitter, evt("stage", "stage", "generate", "state", "live", "text", "大模型起草叙述性内容…"));
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            if (!"ai".equals(str(f.get("src")))) continue;
            send(emitter, evt("nstart", "index", i, "label", f.get("label")));
            String text = narrative(emitter, i, f, profile, record);
            f.put("val", text);
            filled++;
            send(emitter, evt("nend", "index", i, "val", text));
        }
        send(emitter, evt("stage", "stage", "generate", "state", "done", "text", "填写完成 · 叙述内容已标记待核对"));

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

        send(emitter, evt("done", "fillCount", filled, "total", total, "status", status));
        emitter.complete();
    }

    /** 叙述字段：优先 LLM 流式起草；失败回落事实拼装 */
    private String narrative(SseEmitter emitter, int index, Map<String, Object> f,
                             Map<String, Object> profile, FormRecord record) {
        String key = str(f.get("key"));
        String label = str(f.get("label"));
        String brief = FormFieldCatalog.narrativeBrief(key);
        boolean isSummary = "summary1".equals(key);
        String sys = "你是高校教师填报材料撰写助手。只依据用户提供的事实数据与素材撰写，禁止编造任何数字、荣誉或经历；"
                + "事实数据中没有的信息直接略过，不要用“某”“若干”等占位。语言平实书面化。直接输出正文，不要输出任何前缀、标题或说明。";
        String user = "栏目：" + label + "。写作要求：" + brief + "。"
                + "教师事实数据（JSON）：" + jsonOf(profile)
                + (isSummary ? "\n\n教师提供的分节素材要点（优先使用，逐条落实；未提供的部分用通用表述带过）：\n" + materialsOf(record) : "");
        StringBuilder sb = new StringBuilder();
        try {
            String full = llm.chatStream(sys, user, piece -> {
                sb.append(piece);
                try {
                    send(emitter, evt("delta", "index", index, "chunk", piece));
                } catch (Exception ignore) {
                }
            });
            return full;
        } catch (Exception e) {
            log.warn("智能填报：LLM 起草失败（{}），回落事实拼装：{}", label, e.getMessage());
            String text = isSummary ? fallbackSummary(profile, record) : fallbackNarrative(key, profile);
            // 回落也按块推送，前端体验一致
            for (String piece : chunk(text, 6)) {
                try {
                    send(emitter, evt("delta", "index", index, "chunk", piece));
                } catch (Exception ignore) {
                }
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
    private String fallbackSummary(Map<String, Object> p, FormRecord record) {
        String name = str(p.get("name")), zc = str(p.get("zc")), xl = str(p.get("xl"));
        StringBuilder b = new StringBuilder("2025 年度，本人立足本职岗位，认真完成各项工作任务，现总结如下：\n");
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

    private Map<String, Object> fieldEvent(int index, Map<String, Object> f, boolean fallback) {
        String basis = str(f.get("basis"));
        if (fallback && !"need".equals(str(f.get("src"))) && !basis.isEmpty()) {
            basis += "（数仓暂不可达，已使用本地快照）";
        }
        Map<String, Object> e = evt("field", "index", index);
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

    private void send(SseEmitter emitter, Map<String, Object> data) throws Exception {
        emitter.send(SseEmitter.event().data(om.writeValueAsString(data)));
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
