package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.service.employment.EmploymentChatService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 智慧就业助手 SSE chat（native-employment）。 */
@RestController
@RequestMapping("/api/v1/employment")
public class EmploymentChatController {

    private final EmploymentChatService chatService;

    public EmploymentChatController(EmploymentChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String q, Authentication auth) {
        Long userId = auth == null ? null : (Long) auth.getPrincipal();
        SseEmitter emitter = new SseEmitter(180000L);
        final Long uid = userId;
        new Thread(new Runnable() {
            public void run() {
                try {
                    chatService.chat(q, uid, emitter);
                } catch (Exception e) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}"));
                        emitter.complete();
                    } catch (Exception ignore) {}
                }
            }
        }, "employment-chat").start();
        return emitter;
    }
}
