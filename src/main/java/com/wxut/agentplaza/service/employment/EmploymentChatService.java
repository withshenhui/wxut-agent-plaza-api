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

        emitter.send(SseEmitter.event().name("tool")
                .data("{\"message\":\"正在读取你的学籍信息并匹配岗位…\"}"));

        List<Map<String, Object>> rec;
        try {
            rec = employmentService.recommend(studentNo);
        } catch (Exception e) {
            log.warn("就业对话：匹配失败 {}", e.getMessage());
            rec = java.util.Collections.emptyList();
        }

        String sys = "你是无锡职业技术大学的「智慧就业助手」。依据系统提供的真实推荐结果，用平实、鼓励的书面语气为学生解读推荐；"
                + "必须包含一个 markdown 表格列出推荐岗位（列：岗位 | 公司 | 城市 | 薪资 | 匹配分），表格后按匹配分从高到低逐条用一两句说明推荐理由与可选短板。"
                + "严禁编造推荐结果之外的岗位、薪资或要求；数据缺失就如实省略。直接输出正文，不要标题和前言。";
        String user = "学生画像：" + om.writeValueAsString(profile)
                + "\n就业意向：" + om.writeValueAsString(intent == null ? java.util.Collections.emptyMap() : intent)
                + "\n系统推荐结果（真实岗位，按匹配分排序）：" + om.writeValueAsString(rec)
                + (rec.isEmpty() ? "\n（推荐结果为空：学生画像缺失或岗位库暂无符合条件岗位，请如实说明并建议其完善意向/稍后再试）" : "");

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
