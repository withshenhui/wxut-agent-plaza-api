package com.wxut.agentplaza.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxut.agentplaza.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
