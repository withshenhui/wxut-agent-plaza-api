package com.wxut.agentplaza.service;

import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.ChatMessageDTO;
import com.wxut.agentplaza.entity.ChatMessage;
import com.wxut.agentplaza.vo.ChatMessageVO;
import com.wxut.agentplaza.vo.ChatSessionVO;

public interface ChatService {
    ChatSessionVO createSession(Long userId, Long agentId, String title);
    PageResult<ChatSessionVO> listSessions(Long userId, int page, int size);
    ChatSessionVO getSession(String sessionId);
    void deleteSession(String sessionId);
    ChatMessageVO sendMessage(String sessionId, Long userId, ChatMessageDTO dto);
    PageResult<ChatMessageVO> listMessages(String sessionId, int page, int size);
}
