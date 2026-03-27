package com.lyh.aiagent.app;

import com.lyh.aiagent.advisors.LoggerAdvisor;
import com.lyh.aiagent.chatmemory.FileBasedChatMemory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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

    private final ChatClient chatClientWithRag;
    private final ChatClient chatClientWithoutRag;
    private final ChatClient routerClient;
    private final ChatClient titleClient;
    private final ChatMemory chatMemory;
    private static final double ROUTER_CONFIDENCE_THRESHOLD = 0.65;
    private static final int CHAT_MEMORY_RETRIEVE_SIZE = 10;

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
            你擅长处理心理相关话题（情绪困扰、压力焦虑、人际关系、自我成长、睡眠问题、职场心理等）。
            即使没有具体的参考资料，你也可以运用认知行为疗法、人本主义等心理学知识帮助用户。
            先理解和共情，再在聊天中给出实用的建议，不要生硬地套框架。

            ## 边界限制
            - 只有以下情况才说"超出范围"：医疗处方、法律咨询、财务规划等非心理咨询领域。
            - 所有心理、情绪、压力、人际关系等问题都在你的专业范围内，不要说超出范围。
            - 如果察觉用户有自伤或极端情绪倾向，立即建议其拨打心理危机热线（如：全国24小时心理援助热线 400-161-9995）或前往专业医疗机构就诊。
            - 明确告知用户你是AI心理咨询助手，无法替代专业心理医生的诊断和治疗。
            """;

    private static final String ROUTER_SYSTEM_PROMPT = """
            你是对话路由器。请判断当前用户消息是否需要调用心理知识库RAG。

            判断标准：
            1) 心理情绪、压力、人际、睡眠、自我成长、咨询建议类，通常 useRag=true
            2) 普通闲聊、问候、泛化常识问题，通常 useRag=false
            3) 不确定时给出较低 confidence，并倾向 useRag=false

            仅返回JSON对象，字段如下：
            - intent: "psychology" | "general" | "smalltalk" | "unknown"
            - useRag: true/false
            - confidence: 0~1 的小数
            - reason: 一句话（不超过20字）
            """;

    public EmotionApp(@Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
                      VectorStore vectorStore) {

        this.chatMemory = new FileBasedChatMemory("data/conversations");

        // RAG配置：基础检索
        RetrievalAugmentationAdvisor raa = RetrievalAugmentationAdvisor.builder()
                // 先做查询改写：把“跟进问题”改写成带上下文的独立问题，再检索
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(ChatClient.builder(dashscopeChatModel))
                        .targetSearchSystem("心理咨询知识库")
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .similarityThreshold(0.65)
                        .topK(5)
                        .build())
                // 关键修复：当检索为空时返回原始query，避免注入默认拒答模板
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();

        chatClientWithRag = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new LoggerAdvisor(),
                        raa
                )
                .build();

        chatClientWithoutRag = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new LoggerAdvisor()
                )
                .build();

        routerClient = ChatClient.builder(dashscopeChatModel).build();
        titleClient = ChatClient.builder(dashscopeChatModel).build();
        log.info("智能路由已启用：先模型分类，再关键词兜底；心理专业问题走RAG（阈值0.65，topK=5）");
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

    public void clearChatMemory(String chatId) {
        chatMemory.clear(chatId);
    }

    // 流式调用
    public Flux<String> doChat(String message, String chatId) {
        logPromptStep("S1_RAW_INPUT", """
                chatId: %s
                userMessage:
                %s
                """.formatted(chatId, message));

        RouteDecision decision = routeWithModel(message);
        boolean psychologicalScope = isPsychologicalScope(message);
        boolean useRag = psychologicalScope || shouldUseRag(decision, message);
        ChatClient activeClient = useRag ? chatClientWithRag : chatClientWithoutRag;
        String extraSystemInstruction = buildExtraSystemInstruction(psychologicalScope);
        logPromptStep("S2_ROUTE_RESULT", """
                modelDecision: %s
                psychologicalScope: %s
                useRag: %s
                selectedClient: %s
                """.formatted(decision, psychologicalScope, useRag, useRag ? "chatClientWithRag" : "chatClientWithoutRag"));
        logPromptStep("S3_SYSTEM_PROMPT", SYSTEM_PROMPT);
        logPromptStep("S3B_EXTRA_SYSTEM_INSTRUCTION", extraSystemInstruction);
        logPromptStep("S4_CHAT_MEMORY", dumpChatMemory(chatId, CHAT_MEMORY_RETRIEVE_SIZE));
        logPromptStep("S5_FINAL_USER_MESSAGE", message);
        logPromptStep("S6_ADVISOR_PARAMS", """
                %s = %s
                %s = %d
                """.formatted(
                CHAT_MEMORY_CONVERSATION_ID_KEY, chatId,
                CHAT_MEMORY_RETRIEVE_SIZE_KEY, CHAT_MEMORY_RETRIEVE_SIZE));
        log.info("chat route: useRag={}, psychologicalScope={}, intent={}, confidence={}, reason={}",
                useRag,
                psychologicalScope,
                decision != null ? decision.getIntent() : "fallback",
                decision != null ? decision.getConfidence() : null,
                decision != null ? decision.getReason() : "keyword-fallback");

        return activeClient
                .prompt()
                .system(extraSystemInstruction)
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, CHAT_MEMORY_RETRIEVE_SIZE))
                .stream()
                .content()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }

    private RouteDecision routeWithModel(String message) {
        try {
            logPromptStep("ROUTER_SYSTEM_PROMPT", ROUTER_SYSTEM_PROMPT);
            logPromptStep("ROUTER_USER_MESSAGE", message);

            RouteDecision decision = routerClient.prompt()
                    .system(ROUTER_SYSTEM_PROMPT)
                    .user(message)
                    .call()
                    .entity(RouteDecision.class);
            logPromptStep("ROUTER_OUTPUT", String.valueOf(decision));
            return decision;
        } catch (Exception e) {
            log.warn("model routing failed, fallback to keyword routing: {}", e.getMessage());
            return null;
        }
    }

    private boolean shouldUseRag(RouteDecision decision, String message) {
        if (decision != null && decision.getUseRag() != null) {
            double confidence = decision.getConfidence() == null ? 0.0 : decision.getConfidence();
            if (confidence >= ROUTER_CONFIDENCE_THRESHOLD) {
                return decision.getUseRag();
            }
        }
        return shouldUseRagByKeywordFallback(message);
    }

    private boolean shouldUseRagByKeywordFallback(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String text = message.toLowerCase();

        // 心理相关关键词直接走RAG
        if (containsAny(text,
                "心理", "情绪", "焦虑", "抑郁", "失眠", "压力", "崩溃", "绝望",
                "创伤", "恐慌", "强迫", "自卑", "内耗", "人际关系", "原生家庭")) {
            return true;
        }

        // 明确求助语义优先走RAG
        if (containsAny(text, "怎么办", "怎么做", "如何", "有什么方法", "建议", "帮帮我")) {
            return true;
        }

        // 短句问候/闲聊不走RAG，降低不必要检索噪声
        if (message.length() <= 10 && containsAny(text, "你好", "在吗", "hi", "hello", "吃了吗", "晚安")) {
            return false;
        }

        // 默认不走RAG，避免泛化问题被心理知识库干扰
        return false;
    }

    private boolean isPsychologicalScope(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase();
        return containsAny(text,
                "心理", "情绪", "焦虑", "抑郁", "失眠", "压力", "崩溃", "绝望",
                "创伤", "恐慌", "强迫", "自卑", "内耗", "人际关系", "原生家庭",
                "难受", "烦", "累", "心情", "痛苦", "无助", "紧张", "担心");
    }

    private String buildExtraSystemInstruction(boolean psychologicalScope) {
        if (!psychologicalScope) {
            return "保持自然、准确，优先直接回答用户当前问题。";
        }
        return """
                回答要求：
                1) 先共情再建议，给出可执行的调节方法
                2) 不要说“超出范围”或类似拒答
                3) 保持自然口语化，简洁回答
                """;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Data
    public static class RouteDecision {
        private String intent;
        private Boolean useRag;
        private Double confidence;
        private String reason;
    }

    private String dumpChatMemory(String chatId, int lastN) {
        List<Message> messages = chatMemory.get(chatId, lastN);
        if (messages == null || messages.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String role = "unknown";
            if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "assistant";
            }
            sb.append("#").append(i + 1).append(" [").append(role).append("]\n")
                    .append(msg.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private void logPromptStep(String step, String content) {
        log.info("[PROMPT_DEBUG] {}\n{}", step, content);
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
