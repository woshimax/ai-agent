package com.lyh.aiagent.config;

import com.lyh.aiagent.chatmemory.FileBasedChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean("manusChatMemory")
    public ChatMemory manusChatMemory() {
        return new FileBasedChatMemory("data/manus-conversations");
    }
}
