package com.wxut.agentplaza.service.employment;

import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.form.FormLlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智慧就业助手：SSE 对话（native-employment）。
 * 事件协议与 native-rag 一致（tool/text/done/error），前端零改造复用。
 * 流程：取学号画像 → 寒暄分流 → 调匹配引擎（规则+LLM精排）→ LLM 组织自然语言（含岗位清单）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmploymentChatService {

    private final EmploymentService employmentService;
    private final SysUserMapper userMapper;
    private final FormLlmService llm;
    private final com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String[] JS_CITIES = {"南京", "无锡", "苏州", "徐州", "常州", "南通", "连云港", "淮安", "盐城", "扬州", "镇江", "泰州", "宿迁"};

    /** 从用户话里规则提取意向（城市/岗位类型），用于"对话即填报" */
    static Map<String, Object> extractIntent(String q) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        if (q == null) return m;
        for (String c : JS_CITIES) {
            if (q.contains(c)) { m.put("city", "[\"" + c + "\"]"); break; }
        }
        if (q.contains("实习")) m.put("jobType", "intern");
        else if (q.contains("全职")) m.put("jobType", "fulltime");
        return m;
    }

    private static final Pattern GREET = Pattern.compile("^(你好|您好|hi|hello|在吗|嗨|哈喽|早上好|中午好|下午好|晚上好)[!！。~～\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("(你是谁|你叫什么|介绍一下你|能做什么|有什么功能|怎么用)");

    public void chat(String q, Long userId, SseEmitter emitter) throws Exception {
        SysUser u = userId == null ? null : userMapper.selectById(userId);
        if (u == null) {
            emitter.send(SseEmitter.event().name("error").data("{\"message\":\"请先登录后使用智慧就业助手\"}"));
            emitter.complete();
            return;
        }
        String studentNo = u.getUsername();

        // 寒暄/身份询问：不查库，直接自我介绍
        if (GREET.matcher(q.trim()).matches() || META.matcher(q).find()) {
            emitter.send(SseEmitter.event().name("tool").data("{\"message\":\"思考中…\"}"));
            String intro = "你好呀～我是**智慧就业助手**。我会读取你的学籍信息（专业、年级），结合校招岗位库为你匹配适合的工作，"
                    + "还会告诉你**每个岗位为什么推荐给你、你还可以补什么短板**。\n\n"
                    + "你可以直接说：\n- “帮我推荐适合我的岗位”\n- “我想找无锡的实习”\n- “我是机械专业，想看装备制造类岗位”";
            for (String piece : intro.split("(?<=。|？|\\n)")) {
                if (piece.trim().isEmpty()) continue;
                emitter.send(SseEmitter.event().name("text").data("{\"data\":" + om.writeValueAsString(piece) + "}"));
            }
            emitter.send(SseEmitter.event().name("done").data("{}"));
            emitter.complete();
            return;
        }

        Map<String, Object> profile = employmentService.profile(studentNo);
        Map<String, Object> intent = employmentService.getIntent(studentNo);
        // 对话即填报：从当前这句话中规则提取意向并合并落库（学生随口说偏好 = 意向收集）
        Map<String, Object> patch = extractIntent(q);
        if (!patch.isEmpty()) {
            Map<String, Object> merged = intent == null ? new java.util.LinkedHashMap<String, Object>() : new java.util.LinkedHashMap<String, Object>(intent);
            merged.putAll(patch);
            try { employmentService.saveIntent(studentNo, merged); intent = employmentService.getIntent(studentNo); } catch (Exception ignore) {}
        }

        emitter.send(SseEmitter.event().name("tool")
                .data("{\"message\":\"正在读取你的学籍信息…\"}"));

        // 首次使用（无任何意向记录）→ 推送意向引导卡（前端渲染可点选的 chips）
        if (intent == null || intent.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("guide").data(
                        "{\"cities\":[\"无锡\",\"苏州\",\"南京\",\"上海\",\"不限\"],"
                      + "\"types\":[\"全职\",\"实习\",\"都看\"],"
                      + "\"pays\":[\"5K 以下\",\"5-8K\",\"8-12K\",\"12K 以上\",\"不限\"]}"));
            } catch (Exception ignore) {}
        }

        // 规则匹配（含事实型推荐理由/短板，不调大模型）→ 秒级出卡；卡片由前端在"思考"结束后展示
        List<Map<String, Object>> rec;
        try {
            rec = employmentService.quickRank(studentNo);
        } catch (Exception e) {
            log.warn("就业对话：匹配失败 {}", e.getMessage());
            rec = java.util.Collections.emptyList();
        }
        try { emitter.send(SseEmitter.event().name("jobs").data(om.writeValueAsString(rec))); } catch (Exception ignore) {}

        String sys = "你是无锡职业技术大学的「智慧就业助手」。请用 2-3 句话给学生一个**总体解读**"
                + "（结合其专业/年级与求职意向，例如本次匹配方向的大致分布、适合优先看哪类岗位）。"
                + "重要：**不要罗列岗位名称、不要逐条写推荐理由或短板、不要说\u201c按匹配度呈现前N条\u201d之类的句子**——"
                + "岗位清单、推荐理由与可补短板已经用卡片形式展示在回复下方，正文重复会显得冗余。"
                + "最后一句固定为：岗位信息来自公开校招渠道，仅供参考，以招聘单位官方发布为准。"
                + "严禁编造卡片之外的信息；数据缺失就如实省略。直接输出正文，不要标题。";
        String user = "学生画像：" + om.writeValueAsString(profile)
                + "\n就业意向：" + om.writeValueAsString(intent == null ? java.util.Collections.emptyMap() : intent)
                + "\n本次共匹配到 " + rec.size() + " 条岗位（明细已在卡片中展示，请只做总体解读）"
                + (rec.isEmpty() ? "\n（推荐结果为空：学生画像缺失或岗位库暂无符合条件岗位，请如实说明并建议其完善意向或稍后再试）" : "");

        final SseEmitter em = emitter;
        llm.chatStream(sys, user, new java.util.function.Consumer<String>() {
            public void accept(String delta) {
                try {
                    em.send(SseEmitter.event().name("text").data("{\"data\":" + om.writeValueAsString(delta) + "}"));
                } catch (Exception ignore) {
                }
            }
        });
        emitter.send(SseEmitter.event().name("done").data("{}"));
        emitter.complete();
    }
}
