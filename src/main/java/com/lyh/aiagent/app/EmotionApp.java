package com.lyh.aiagent.app;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.lyh.aiagent.advisors.LoggerAdvisor;
import com.lyh.aiagent.advisors.RereadingAdvisor;
import com.lyh.aiagent.chatmemory.FileBasedChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Component
@Slf4j
public class EmotionApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一位深耕恋爱心理领域的专家，擅长以温暖、共情的方式帮助用户解决恋爱难题。

            ## 回复风格
            - 始终以共情为主，先接纳和认可用户的感受，再给出建议。
            - 语气温暖、真诚，像一位值得信赖的朋友，避免说教和居高临下。
            - 多使用"我能理解""这种感受很正常""你愿意说出来本身就很勇敢"等共情表达。

            ## 边界限制
            - 只回答恋爱情感相关的问题（单身、恋爱、已婚场景）。
            - 如果用户询问恋爱情感之外的话题，礼貌提醒："我是恋爱心理方面的专家，这个问题可能超出了我的专业范围，建议你咨询相关领域的专业人士。"
            - 不提供医疗、法律、财务等专业建议。
            - 如果察觉用户有自伤或极端情绪倾向，建议其寻求专业心理危机干预。

            ## 输出格式
            每次回复严格按以下结构组织：
            1. **共情回应**：先回应用户的情绪和感受，让用户感到被理解和接纳。
            2. **问题分析**：简要分析用户面临的核心问题。
            3. **行动建议**：分步骤给出具体、可执行的建议方案（2-4步）。

            ## 引导提问
            围绕单身、恋爱、已婚三种状态引导用户：
            - 单身：社交圈拓展、追求心仪对象的困扰。
            - 恋爱：沟通方式、习惯差异引发的矛盾。
            - 已婚：家庭责任分配、亲属关系处理。
            引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。
            """;

    public EmotionApp(@Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel) {

        ChatMemory chatMemory = new FileBasedChatMemory("data/conversations");
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new LoggerAdvisor(),
                        new RereadingAdvisor()
                )
                .build();
    }

    // 同步调用
//    public String doChat(String message, String chatId) {
//        ChatResponse response = chatClient
//                .prompt()
//                .user(message)
//                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
//                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
//                .call()
//                .chatResponse();
//
//        String content = response.getResult().getOutput().getText();
//        log.info("content: {}", content);
//        return content;
//    }

    // 流式调用
    public Flux<String> doChat(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .stream()
                .content()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }

    public EmotionReport doChatWithReport(String message, String chatId) {
        EmotionReport loveReport = Mono.fromCallable(() -> chatClient
                        .prompt()
                        .system(SYSTEM_PROMPT + "每次对话后都要生成情感分析结果，标题为{用户名}的情感分析报告，内容为建议列表")
                        .user(message)
                        .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                        .call()
                        .entity(EmotionReport.class))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                .block();
        //.entity() 使用了 Spring AI的结构化输出能力：
        //  - 自动根据 EmotionReport类生成 JSON Schema ——比如这里report两个属性title和suggestions
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

}