package com.lyh.aiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lyh.aiagent.mapper.ChatMapper;
import com.lyh.aiagent.model.Chat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMapper chatMapper;

    public Chat createChat(Long userId) {
        Chat chat = new Chat();
        chat.setUserId(userId);
        chat.setChatId(UUID.randomUUID().toString().replace("-", ""));
        chat.setCreateTime(new Date());
        chat.setUpdateTime(new Date());
        chatMapper.insert(chat);
        return chat;
    }

    public List<Chat> listByUserId(Long userId) {
        return chatMapper.selectList(
                new QueryWrapper<Chat>()
                        .eq("user_id", userId)
                        .orderByDesc("pinned")
                        .orderByDesc("create_time")
        );
    }

    public Chat getByChatId(String chatId) {
        return chatMapper.selectOne(new QueryWrapper<Chat>().eq("chat_id", chatId));
    }

    public void updateChatName(String chatId, String chatName) {
        Chat chat = getByChatId(chatId);
        if (chat != null) {
            chat.setChatName(chatName);
            chat.setUpdateTime(new Date());
            chatMapper.updateById(chat);
        }
    }

    public void pinChat(String chatId, Boolean pinned) {
        Chat chat = getByChatId(chatId);
        if (chat != null) {
            chat.setPinned(pinned);
            chat.setUpdateTime(new Date());
            chatMapper.updateById(chat);
        }
    }

    public void deleteByChatId(String chatId) {
        chatMapper.delete(new QueryWrapper<Chat>().eq("chat_id", chatId));
    }
}
