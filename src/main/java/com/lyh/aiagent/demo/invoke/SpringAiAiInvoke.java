package com.lyh.aiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource
    private ChatModel dashscopeChatModel;

    @Override
    public void run(String... args) throws Exception {
        //测试一下LangchainAiInvoke，先注释
//        AssistantMessage output = dashscopeChatModel.call(new Prompt("你好，我是福建医科大学需加温医生"))
//                .getResult()
//                .getOutput();
//        System.out.println(output.getText());
    }
}