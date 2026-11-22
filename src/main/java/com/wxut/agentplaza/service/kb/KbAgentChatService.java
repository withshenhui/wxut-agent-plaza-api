package com.wxut.agentplaza.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wxut.agentplaza.entity.Agent;
import com.wxut.agentplaza.entity.Kb;
import com.wxut.agentplaza.entity.SysUser;
import com.wxut.agentplaza.mapper.AgentMapper;
import com.wxut.agentplaza.mapper.KbMapper;
import com.wxut.agentplaza.mapper.SysUserMapper;
import com.wxut.agentplaza.service.form.FormLlmService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native RAG agent chat: retrieve (auto-bound dept KB + manual kb_ids) -> prompt
 * with [n] citations -> stream answer + citation event.
 */
@Service
public class KbAgentChatService {

    private final AgentMapper agentMapper;
    private final KbMapper kbMapper;
    private final SysUserMapper userMapper;
    private final KbSearchService searchService;
    private final FormLlmService llm;

    public KbAgentChatService(AgentMapper agentMapper, KbMapper kbMapper, SysUserMapper userMapper,
                              KbSearchService searchService, FormLlmService llm) {
        this.agentMapper = agentMapper; this.kbMapper = kbMapper;
        this.userMapper = userMapper; this.searchService = searchService;
        this.llm = llm;
    }

    public static class Citation {
        public int no;
        public String type, doc, loc, q, a, txt;
    }

    public void chat(Long agentId, String q, Long userId, SseEmitter emitter) {
        try {
            Agent agent = agentMapper.selectById(agentId);
            if (agent == null || !"native-rag".equals(agent.getAgentType())) {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"not a native-rag agent\"}"));
                emitter.complete();
                return;
            }

            // resolve KBs: manual kb_ids + asker's dept KB (auto-bind)
            List<Long> kbIds = new ArrayList<Long>();
            if (agent.getKbIds() != null && !agent.getKbIds().trim().isEmpty()) {
                for (String s : agent.getKbIds().split(",")) {
                    try { kbIds.add(Long.parseLong(s.trim())); } catch (Exception ignore) {}
                }
            }
            SysUser asker = userId == null ? null : userMapper.selectById(userId);
            if (asker != null && asker.getCollegeId() != null) {
                for (Kb kb : kbMapper.selectList(new QueryWrapper<Kb>()
                        .eq("visibility", "dept").eq("dept_id", asker.getCollegeId()).eq("status", "ok"))) {
                    if (!kbIds.contains(kb.getId())) kbIds.add(kb.getId());
                }
            }
            if (kbIds.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"该智能体未绑定知识库\"}"));
                emitter.complete();
                return;
            }

            emitter.send(SseEmitter.event().name("tool")
                    .data("{\"message\":\"正在检索知识库…\"}"));

            List<KbSearchService.Hit> hits = searchService.search(kbIds, q, 5);
            List<Citation> citations = new ArrayList<Citation>();
            StringBuilder ctx = new StringBuilder();
            int n = 1;
            for (KbSearchService.Hit h : hits) {
                Citation c = new Citation();
                c.no = n;
                c.type = h.type; c.doc = h.doc; c.loc = h.loc;
                c.q = h.q; c.a = h.a; c.txt = h.txt;
                citations.add(c);
                ctx.append('[').append(n).append("] ");
                if ("qa".equals(h.type)) {
                    ctx.append("问：").append(h.q).append("\n答：").append(h.a).append("\n\n");
                } else {
                    ctx.append(h.txt).append("\n\n");
                }
                n++;
            }

            String system = "你是无锡职业技术大学的制度问答助手。仅依据下面给出的参考资料回答；"
                    + "回答中用 [编号] 标注每处依据；资料不足以回答时如实说明并建议咨询主管部门。"
                    + "涉及办理流程按\"条件→材料→流程→部门\"组织回答。";
            String user = "参考资料：\n" + ctx + "\n问题：" + q;

            // citation event before streaming (frontend renders reference blocks)
            StringBuilder cb = new StringBuilder("[");
            for (int i = 0; i < citations.size(); i++) {
                Citation c = citations.get(i);
                if (i > 0) cb.append(',');
                cb.append("{\"no\":").append(c.no).append(",\"type\":\"").append(c.type)
                        .append("\",\"doc\":\"").append(esc(c.doc)).append("\",\"loc\":\"").append(esc(c.loc))
                        .append("\",\"q\":\"").append(esc(c.q)).append("\",\"a\":\"").append(esc(c.a))
                        .append("\",\"txt\":\"").append(esc(c.txt == null ? "" :
                                (c.txt.length() > 600 ? c.txt.substring(0, 600) : c.txt))).append("\"}");
            }
            cb.append(']');
            emitter.send(SseEmitter.event().name("citation").data(cb.toString()));

            final SseEmitter em = emitter;
            llm.chatStream(system, user, new java.util.function.Consumer<String>() {
                public void accept(String delta) {
                    try {
                        em.send(SseEmitter.event().name("text").data(
                                "{\"data\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(delta) + "}"));
                    } catch (Exception ignore) {}
                }
            });
            emitter.send(SseEmitter.event().name("done").data("{}"));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"" + esc(e.getMessage()) + "\"}"));
            } catch (Exception ignore) {}
            emitter.completeWithError(e);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
