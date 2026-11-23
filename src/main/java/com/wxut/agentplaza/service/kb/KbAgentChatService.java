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
            // 公共知识库（is_shared）：学校简介等公共内容对所有智能体生效，自动并入检索范围
            for (Kb kb : kbMapper.selectList(new QueryWrapper<Kb>().eq("is_shared", 1))) {
                if (!kbIds.contains(kb.getId())) kbIds.add(kb.getId());
            }
            if (kbIds.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"该智能体未绑定知识库\"}"));
                emitter.complete();
                return;
            }

            // 寒暄/闲聊判断：纯规则（LLM 分类一次往返 10s+ 拖慢首字，弃用；未命中按知识问题处理最坏只是多查一次库）
            String agentName = agent.getName() == null ? "制度助手" : agent.getName();
            if (isChitchatFast(q)) {
                emitter.send(SseEmitter.event().name("tool")
                        .data("{\"message\":\"思考中…\"}"));
                String casual = casualReply(agentName, agent.getDescription(), q, llm);
                if (casual != null && !casual.trim().isEmpty()) {
                    for (String piece : casual.split("(?<=。|！|？|\\n)")) {
                        if (piece.trim().isEmpty()) continue;
                        emitter.send(SseEmitter.event().name("text")
                                .data("{\"data\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(piece) + "}"));
                    }
                    emitter.send(SseEmitter.event().name("done").data("{}"));
                    emitter.complete();
                    return;
                }
            }

            emitter.send(SseEmitter.event().name("tool")
                    .data("{\"message\":\"正在检索知识库…\"}"));

            List<KbSearchService.Hit> hits = searchService.search(kbIds, q, 5);
            // 同一文档命中多个片段时合并为一个引用编号（正文只出现一次该文档）；
            // ctx 中把同文档各片段拼接在同一个 [n] 下，LLM 可见的资料内容不减少。
            List<Citation> citations = new ArrayList<Citation>();
            List<String> ctxParts = new ArrayList<String>();
            java.util.Map<String, Integer> idxByDoc = new java.util.HashMap<String, Integer>();
            java.util.Map<Integer, java.util.LinkedHashSet<String>> locParts = new java.util.HashMap<Integer, java.util.LinkedHashSet<String>>();
            for (KbSearchService.Hit h : hits) {
                String key = h.doc == null || h.doc.trim().isEmpty() ? "__hit_" + citations.size() : h.doc.trim();
                String body = "qa".equals(h.type) ? ("问：" + h.q + "\n答：" + h.a) : h.txt;
                Integer idx = idxByDoc.get(key);
                if (idx == null) {
                    Citation c = new Citation();
                    c.no = citations.size() + 1;
                    c.type = h.type; c.doc = h.doc; c.loc = h.loc;
                    c.q = h.q; c.a = h.a; c.txt = h.txt;
                    citations.add(c);
                    idxByDoc.put(key, citations.size() - 1);
                    ctxParts.add(body);
                    java.util.LinkedHashSet<String> ls = new java.util.LinkedHashSet<String>();
                    if (h.loc != null && !h.loc.isEmpty()) ls.add(h.loc);
                    locParts.put(citations.size() - 1, ls);
                } else {
                    Citation c = citations.get(idx);
                    ctxParts.set(idx, ctxParts.get(idx) + "\n" + body);
                    if (c.txt == null || (h.txt != null && h.txt.length() > c.txt.length())) c.txt = h.txt;
                    if (h.loc != null && !h.loc.isEmpty()) locParts.get(idx).add(h.loc);
                }
            }
            // 合并后 loc：去重 + 相同前缀折叠（"第四章 … · 第十五条、第十八条"，不再重复罗列章节）
            for (int i = 0; i < citations.size(); i++) {
                java.util.List<String> ps = new ArrayList<String>(locParts.get(i));
                if (ps.isEmpty()) { citations.get(i).loc = null; continue; }
                String merged;
                int cut = ps.get(0).indexOf(" · ");
                boolean sharePrefix = cut > 0;
                if (sharePrefix) {
                    String common = ps.get(0).substring(0, cut);
                    for (String p : ps) if (!p.startsWith(common + " · ")) { sharePrefix = false; break; }
                }
                if (sharePrefix) {
                    StringBuilder sb = new StringBuilder(ps.get(0).substring(0, cut)).append(" · ");
                    for (int j = 0; j < ps.size(); j++) {
                        if (j > 0) sb.append('、');
                        sb.append(ps.get(j).substring(ps.get(0).indexOf(" · ") + 3));
                    }
                    merged = sb.toString();
                } else {
                    merged = String.join("、", ps);
                }
                citations.get(i).loc = merged.length() > 80 ? merged.substring(0, 80) : merged;
            }
            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < citations.size(); i++) {
                ctx.append('[').append(citations.get(i).no).append("] ").append(ctxParts.get(i)).append("\n\n");
            }

            String system = "你是\"" + agent.getName() + "\"，无锡职业技术大学的制度问答智能体。仅依据下面给出的参考资料回答；"
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

    /**
     * 规则快速短路：命中寒暄模式（你好/在吗/你是谁/能干什么/谢谢等）直接判定，
     * 免去 LLM 分类的秒级延迟。短消息（<=6 字）更宽松——长句几乎必是知识问题。
     */
    private static boolean isChitchatFast(String q) {
        if (q == null) return true;
        String s = q.trim();
        if (s.isEmpty()) return true;
        if (s.length() > 30) return false;
        String low = s.toLowerCase();
        if (s.length() <= 6) {
            // 极短消息：含问号/数字多半是查询，纯问候词直接放行
            if (s.matches(".*[\\d?？].*")) return false;
            return s.contains("你好") || s.contains("您好") || low.contains("hi")
                    || low.contains("hello") || s.contains("在吗") || s.contains("在么")
                    || s.contains("嗨") || s.contains("哈喽") || s.equals("你是谁")
                    || s.contains("谢谢") || s.contains("感谢") || s.contains("再见")
                    || s.contains("拜拜");
        }
        // 中等长度：身份/能力/寒暄关键词（"怎么"开头的办理类问题不算寒暄）
        boolean chitchatWord = low.contains("你是谁") || low.contains("你叫什么") || low.contains("你是什么")
                || low.contains("自我介绍") || low.contains("你能干什么") || low.contains("你能做什么")
                || low.contains("你会做什么") || low.contains("你有什么功能") || low.contains("你可以做什么")
                || low.contains("你有什么本事") || low.contains("怎么使用") || low.contains("怎么用你")
                || (low.contains("你") && low.contains("是谁"))
                || low.matches("^(你好|您好|哈喽|嗨|hi|hello|在吗|在么)[,，。!！~～\\s].*");
        boolean knowledgeHint = low.contains("流程") || low.contains("规定") || low.contains("条件")
                || low.contains("怎么办") || low.contains("怎么办理") || low.contains("有哪些") || low.contains("要求");
        return chitchatWord && !knowledgeHint;
    }

    /**
     * 判断用户输入是否为寒暄/闲聊/与制度知识无关的问题（如"你是谁""你好""你能干什么"）。
     * 走一次轻量 LLM 分类，判断失败时保守返回 false（按知识问题处理，不会答错）。
     */
    private boolean isChitchat(String agentName, String agentDesc, String q, FormLlmService llm) {
        try {
            String sys = "你是分类器。判断用户消息是否属于：打招呼、寒暄、询问你的身份/能力、或与制度/办事流程/学校知识完全无关的闲聊。"
                    + "只回答 YES 或 NO。";
            String usr = "智能体名称：" + agentName + "（职能：" + (agentDesc == null ? "" : agentDesc) + "）\n用户消息：" + q;
            String r = llm.chat(sys, usr);
            return r != null && r.trim().toUpperCase().startsWith("Y");
        } catch (Exception e) {
            return false;
        }
    }

    /** 寒暄模式下以智能体身份直接回答（不查知识库） */
    private String casualReply(String agentName, String agentDesc, String q, FormLlmService llm) {
        try {
            String sys = "你是\"" + agentName + "\"，无锡职业技术大学的校园智能体"
                    + (agentDesc != null && !agentDesc.isEmpty() ? "（职能：" + agentDesc + "）" : "")
                    + "。用户正在和你寒暄或询问你的身份/能力。请自然、简短地回应："
                    + "先介绍自己是谁（报出名字和职能），再根据用户的话回应；"
                    + "可以提一句你擅长回答学校制度与办事流程问题且答案带原文出处；"
                    + "不要编造具体制度内容（那需要查知识库），不要长篇大论，控制在 3 句话内。";
            return llm.chat(sys, q);
        } catch (Exception e) {
            return null;
        }
    }
}
