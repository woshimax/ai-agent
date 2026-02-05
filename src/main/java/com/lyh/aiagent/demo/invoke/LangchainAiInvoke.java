package com.lyh.aiagent.demo.invoke;

import com.lyh.aiagent.config.DashscopeProperties;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class LangchainAiInvoke implements CommandLineRunner {


    @Resource
    private DashscopeProperties dashscopeProperties;

    @Override
    public void run(String... args) throws Exception {
        //注释一下，测试Ollama接入springai
//        ChatLanguageModel qwenModel = QwenChatModel.builder()
//                .apiKey(dashscopeProperties.getApiKey())
//                .modelName("qwen-max")
//                .build();
//        String answer = qwenModel.chat("我是投标king游昊昌");
//        System.out.println(answer);
    }
}
