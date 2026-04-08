package com.wxut.agentplaza.controller;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.dto.ChatMessageDTO;
import com.wxut.agentplaza.service.ChatService;
import com.wxut.agentplaza.vo.ChatMessageVO;
import com.wxut.agentplaza.vo.ChatSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "聊天管理")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions")
    @Operation(summary = "创建聊天会话")
    public Result<ChatSessionVO> createSession(Authentication auth, @RequestParam Long agentId,
                                                @RequestParam(required = false) String title) {
        Long userId = (Long) auth.getPrincipal();
        return Result.success(chatService.createSession(userId, agentId, title));
    }

    @GetMapping("/sessions")
    @Operation(summary = "获取会话列表")
    public Result<PageResult<ChatSessionVO>> listSessions(Authentication auth,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) auth.getPrincipal();
        return Result.success(chatService.listSessions(userId, page, size));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "获取会话详情")
    public Result<ChatSessionVO> getSession(@PathVariable String sessionId) {
        return Result.success(chatService.getSession(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除会话")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success();
    }

    @PostMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "发送消息")
    public Result<ChatMessageVO> sendMessage(@PathVariable String sessionId, Authentication auth,
                                             @Valid @RequestBody ChatMessageDTO dto) {
        Long userId = (Long) auth.getPrincipal();
        return Result.success(chatService.sendMessage(sessionId, userId, dto));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "获取消息列表")
    public Result<PageResult<ChatMessageVO>> listMessages(@PathVariable String sessionId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return Result.success(chatService.listMessages(sessionId, page, size));
    }
}
