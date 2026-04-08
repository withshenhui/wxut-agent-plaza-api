package com.wxut.agentplaza.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxut.agentplaza.common.PageResult;
import com.wxut.agentplaza.dto.ChatMessageDTO;
import com.wxut.agentplaza.entity.Agent;
import com.wxut.agentplaza.entity.ChatMessage;
import com.wxut.agentplaza.entity.ChatSession;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.AgentMapper;
import com.wxut.agentplaza.mapper.ChatMessageMapper;
import com.wxut.agentplaza.mapper.ChatSessionMapper;
import com.wxut.agentplaza.service.ChatService;
import com.wxut.agentplaza.vo.ChatMessageVO;
import com.wxut.agentplaza.vo.ChatSessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentMapper agentMapper;

    @Override
    public ChatSessionVO createSession(Long userId, Long agentId, String title) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BusinessException("智能体不存在");
        ChatSession session = new ChatSession();
        session.setAgentId(agentId);
        session.setUserId(userId);
        session.setTitle(title != null ? title : agent.getName());
        sessionMapper.insert(session);
        return toSessionVO(session);
    }

    @Override
    public PageResult<ChatSessionVO> listSessions(Long userId, int page, int size) {
        Page<ChatSession> result = sessionMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getUserId, userId).orderByDesc(ChatSession::getUpdatedAt));
        List<ChatSessionVO> vos = result.getRecords().stream().map(this::toSessionVO).collect(Collectors.toList());
        PageResult<ChatSessionVO> pr = new PageResult<>();
        pr.setRecords(vos);
        pr.setTotal(result.getTotal());
        pr.setPage(result.getCurrent());
        pr.setSize(result.getSize());
        return pr;
    }

    @Override
    public ChatSessionVO getSession(String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("会话不存在");
        ChatSessionVO vo = toSessionVO(session);
        List<ChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId).orderByAsc(ChatMessage::getCreatedAt));
        vo.setMessages(messages.stream().map(this::toMessageVO).collect(Collectors.toList()));
        return vo;
    }

    @Override
    public void deleteSession(String sessionId) {
        sessionMapper.deleteById(sessionId);
    }

    @Override
    public ChatMessageVO sendMessage(String sessionId, Long userId, ChatMessageDTO dto) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("会话不存在");
        if (!session.getUserId().equals(userId)) throw new BusinessException("无权操作此会话");
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setSender("user");
        userMsg.setContent(dto.getContent());
        messageMapper.insert(userMsg);
        Agent agent = agentMapper.selectById(session.getAgentId());
        ChatMessage agentMsg = new ChatMessage();
        agentMsg.setSessionId(sessionId);
        agentMsg.setSender("agent");
        agentMsg.setContent("你好！我是" + (agent != null ? agent.getName() : "AI助手") + "，我已收到您的消息，正在为您处理中...");
        messageMapper.insert(agentMsg);
        return toMessageVO(agentMsg);
    }

    @Override
    public PageResult<ChatMessageVO> listMessages(String sessionId, int page, int size) {
        Page<ChatMessage> result = messageMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId).orderByAsc(ChatMessage::getCreatedAt));
        PageResult<ChatMessageVO> pr = new PageResult<>();
        pr.setRecords(result.getRecords().stream().map(this::toMessageVO).collect(Collectors.toList()));
        pr.setTotal(result.getTotal());
        pr.setPage(result.getCurrent());
        pr.setSize(result.getSize());
        return pr;
    }

    private ChatSessionVO toSessionVO(ChatSession s) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(s.getId());
        vo.setAgentId(s.getAgentId());
        vo.setTitle(s.getTitle());
        vo.setCreatedAt(s.getCreatedAt());
        Agent agent = agentMapper.selectById(s.getAgentId());
        if (agent != null) {
            vo.setAgentName(agent.getName());
            vo.setAgentIcon(agent.getIcon());
        }
        return vo;
    }

    private ChatMessageVO toMessageVO(ChatMessage m) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(m.getId());
        vo.setSender(m.getSender());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }
}
