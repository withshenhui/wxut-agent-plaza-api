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
        chatService.chat(agentId, q, userId, emitter);
        return emitter;
    }
}
