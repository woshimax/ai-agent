package com.lyh.aiagent.app;

import com.lyh.aiagent.advisors.LoggerAdvisor;
import com.lyh.aiagent.chatmemory.FileBasedChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Component
@Slf4j
public class EmotionApp {

    private final ChatClient chatClient;
    private final ChatClient titleClient;
    private final ChatMemory chatMemory;

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一位专业的心理咨询师，拥有丰富的心理学知识和临床咨询经验，同时也是用户值得信赖的倾听者。

            ## 对话原则
            - 像真人朋友聊天一样自然对话，不要像客服或AI助手。
            - 用户打招呼（"你好""在吗""嗨"）就正常回应，简短亲切，不要急着分析问题或引导话题。
            - 用户闲聊就陪聊，用户倾诉再共情，用户求助再给建议。根据用户的意图调整回复方式。
            - 语气温暖真诚，适当用口语词（"嗯""哎""其实""说实话"），像私聊一样。
            - 回复简洁，通常3-5句话，不要一次说太多，留空间让用户继续说。
            - 不要用编号列表、加粗标题、分段式结构，用自然的段落表达。

            ## 专业能力
            当用户聊到心理相关话题时（情绪困扰、压力焦虑、人际关系、自我成长、睡眠问题、职场心理等），自然地融入你的专业分析和建议，但不要生硬地套框架。先理解和共情，再在聊天中给出实用的建议。善于运用认知行为疗法、人本主义等心理学方法帮助用户。

            ## 边界限制
            - 专注心理咨询领域，其他专业问题（医疗处方、法律、财务等）礼貌说明超出范围。
            - 如果察觉用户有自伤或极端情绪倾向，立即建议其拨打心理危机热线（如：全国24小时心理援助热线 400-161-9995）或前往专业医疗机构就诊。
            - 明确告知用户你是AI心理咨询助手，无法替代专业心理医生的诊断和治疗。
            """;

    public EmotionApp(@Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
                      VectorStore vectorStore) {

        this.chatMemory = new FileBasedChatMemory("data/conversations");

        // RAA：检索增强 advisor，内置 query 改写 + 向量检索
        RetrievalAugmentationAdvisor raa = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(ChatClient.builder(dashscopeChatModel))
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .similarityThreshold(0.5)
                        .topK(5)
                        .build())
                .build();

        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new LoggerAdvisor(),
                        raa
                )
                .build();

        titleClient = ChatClient.builder(dashscopeChatModel).build();
        log.info("本地 RAG 知识库检索已启用（PgVector）");
    }

    /**
     * 根据用户消息生成对话标题，闲聊/问候返回 null
     */
    public String generateTitle(String message) {
        String result = titleClient.prompt()
                .system("你是一个对话标题生成器。根据用户的消息生成一个简短的对话标题（2-8个字）。" +
                        "只输出标题文字，不要加任何标点符号和引号。" +
                        "如果消息只是打招呼或闲聊（比如你好、hi、在吗、hello），直接输出空字符串，什么都不要输出。")
                .user(message)
                .call()
                .content();
        if (result == null) return null;
        result = result.trim();
        return result.isEmpty() ? null : result;
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

    /**
     * 获取历史消息，返回 [{role: "user"/"ai", content: "..."}]
     */
    public List<Map<String, String>> getChatHistory(String chatId, int lastN) {
        List<Message> messages = chatMemory.get(chatId, lastN);
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : messages) {
            String role;
            if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "ai";
            } else {
                continue; // 跳过 system message
            }
            String content = msg.getText();
            // 连续 AI 消息合并（流式存储导致的碎片），user 消息不合并
            if ("ai".equals(role) && !result.isEmpty() && "ai".equals(result.get(result.size() - 1).get("role"))) {
                Map<String, String> last = result.get(result.size() - 1);
                last.put("content", last.get("content") + content);
            } else {
                Map<String, String> item = new HashMap<>();
                item.put("role", role);
                item.put("content", content);
                result.add(item);
            }
        }
        return result;
    }

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

    /**
     * 基于完整对话历史生成心理分析报告，不污染对话记忆
     */
    public EmotionReport generateReport(String chatId) {
        List<Map<String, String>> history = getChatHistory(chatId, 100);
        StringBuilder context = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "用户" : "咨询师";
            context.append(role).append("：").append(msg.get("content")).append("\n");
        }

        EmotionReport report = titleClient
                .prompt()
                .system("你是一位专业的心理咨询报告撰写专家。根据以下对话记录，生成一份结构化的心理分析报告，包含以下字段：\n" +
                        "- title：简短的报告标题（如\"关于焦虑情绪的心理分析报告\"）\n" +
                        "- problems：识别出的核心问题列表（2-4条，每条简明扼要描述用户面临的具体问题）\n" +
                        "- emotionState：用户当前的整体心理/情绪状态评估（一段话，100字左右）\n" +
                        "- shortTermAdvice：短期调适建议（2-4条，立即可执行的方法）\n" +
                        "- longTermAdvice：长期改善建议（2-4条，需要持续践行的方向）")
                .user(context.toString())
                .call()
                .entity(EmotionReport.class);
        log.info("generateReport: {}", report);
        return report;
    }

}