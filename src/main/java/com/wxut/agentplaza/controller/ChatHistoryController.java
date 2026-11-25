package com.wxut.agentplaza.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.ChatMessage;
import com.wxut.agentplaza.entity.ChatSession;
import com.wxut.agentplaza.mapper.ChatMessageMapper;
import com.wxut.agentplaza.mapper.ChatSessionMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.time.LocalDateTime;;

/** 服务端对话历史（跟登录人走）：前端对话完成后存，页面加载时取 */
@Tag(name = "对话历史（服务端）")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    /** 取当前用户+指定智能体的全部对话（含消息），按更新时间倒序 */
    @GetMapping("/history")
    @Operation(summary = "取对话历史（登录人+agentId）")
    public Result<List<Map<String, Object>>> history(Authentication auth, @RequestParam Long agentId) {
        Long userId = (Long) auth.getPrincipal();
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getAgentId, agentId)
                        .orderByDesc(ChatSession::getUpdatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatSession s : sessions) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().toString());
            List<ChatMessage> msgs = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, s.getId())
                            .orderByAsc(ChatMessage::getCreatedAt));
            List<Map<String, Object>> msgList = new ArrayList<>();
            for (ChatMessage msg : msgs) {
                Map<String, Object> mm = new HashMap<>();
                mm.put("sender", msg.getSender());
                mm.put("content", msg.getContent());
                mm.put("createdAt", msg.getCreatedAt() == null ? "" : msg.getCreatedAt().toString());
                msgList.add(mm);
            }
            m.put("messages", msgList);
            out.add(m);
        }
        return Result.success(out);
    }

    /** 保存一轮对话（用户消息+AI回复），session 不存在则自动建 */
    @PostMapping("/save")
    @Operation(summary = "保存一轮对话")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> save(Authentication auth, @RequestBody Map<String, Object> body) {
        Long userId = (Long) auth.getPrincipal();
        Long agentId = Long.parseLong(String.valueOf(body.get("agentId")));
        String sessionId = (String) body.get("sessionId");
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title"));

        ChatSession session;
        if (sessionId != null && !sessionId.isEmpty()) {
            session = sessionMapper.selectById(sessionId);
            if (session == null || !session.getUserId().equals(userId)) {
                session = null;
            }
        } else {
            session = null;
        }
        if (session == null) {
            session = new ChatSession();
            session.setAgentId(agentId);
            session.setUserId(userId);
            session.setTitle(title.isEmpty() ? "对话" : title);
            sessionMapper.insert(session);
        } else {
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                ChatMessage m = new ChatMessage();
                m.setSessionId(session.getId());
                m.setSender(String.valueOf(msg.get("sender")));
                m.setContent(String.valueOf(msg.get("content")));
                messageMapper.insert(m);
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("sessionId", session.getId());
        return Result.success(out);
    }

    /** 删除会话（连同消息） */
    @DeleteMapping("/history/{sessionId}")
    @Operation(summary = "删除一条对话历史")
    public Result<Void> deleteHistory(Authentication auth, @PathVariable String sessionId) {
        Long userId = (Long) auth.getPrincipal();
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.error(403, "无权删除该会话");
        }
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        return Result.success();
    }
}
