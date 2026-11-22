package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.service.kb.KbAgentChatService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Native-RAG agent SSE chat. */
@RestController
@RequestMapping("/api/v1/kb")
public class KbAgentChatController {

    private final KbAgentChatService chatService;

    public KbAgentChatController(KbAgentChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long agentId, @RequestParam String q, Authentication auth) {
        Long userId = auth == null ? null : (Long) auth.getPrincipal();
        SseEmitter emitter = new SseEmitter(180000L);
        // Spring MVC async：controller 线程里同步 send 会被容器攒到方法返回才刷出（无打字机效果）。
        // 必须把对话流水线放到容器线程外执行，emitter 先返回，SSE 事件才能逐块到达客户端。
        final Long uid = userId;
        new java.lang.Thread(new java.lang.Runnable() {
            public void run() {
                try {
                    chatService.chat(agentId, q, uid, emitter);
                } catch (Exception e) {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("error").data("{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}"));
                        emitter.complete();
                    } catch (Exception ignore) {}
                }
            }
        }, "kb-chat-" + agentId).start();
        return emitter;
    }
}
