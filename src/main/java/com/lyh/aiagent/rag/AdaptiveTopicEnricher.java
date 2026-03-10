package com.lyh.aiagent.rag;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.lyh.aiagent.rag.DocumentMetadataSchema.*;

/**
 * 自适应主题分类增强器
 * 根据已有文档的主题分布，智能归类新文档
 * - 如果内容属于已有主题，归入现有主题
 * - 如果是新主题，由LLM提出新的主题名称
 */
@Slf4j
@Component
public class AdaptiveTopicEnricher implements DocumentTransformer {

    private final ChatClient chatClient;
    private final JdbcTemplate pgJdbcTemplate;

    public AdaptiveTopicEnricher(
            @Qualifier("dashscopeChatModel") ChatModel chatModel,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.pgJdbcTemplate = pgJdbcTemplate;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        // 1. 获取现有主题分布
        List<String> existingTopics = getExistingTopics();
        log.debug("现有主题数量: {}, 主题列表: {}", existingTopics.size(), existingTopics);

        // 2. 批量分类文档
        List<TopicClassification> classifications = classifyBatch(documents, existingTopics);

        // 3. 应用分类结果
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            TopicClassification classification = classifications.get(i);

            doc.getMetadata().put(TOPIC, classification.getPrimaryTopic());
            if (classification.getSubTopics() != null && !classification.getSubTopics().isEmpty()) {
                doc.getMetadata().put(SUB_TOPICS, String.join(",", classification.getSubTopics()));
            }
            if (classification.getQuestionType() != null && !classification.getQuestionType().isEmpty()) {
                doc.getMetadata().put(QUESTION_TYPE, classification.getQuestionType());
            }
        }

        return documents;
    }

    /**
     * 获取现有文档的主题分布（按出现频率排序）
     */
    private List<String> getExistingTopics() {
        try {
            return pgJdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'topic' as topic " +
                            "FROM vector_store " +
                            "WHERE metadata->>'topic' IS NOT NULL " +
                            "GROUP BY topic " +
                            "ORDER BY COUNT(*) DESC " +
                            "LIMIT 20",
                    String.class);
        } catch (Exception e) {
            log.debug("查询现有主题失败（可能是首次导入）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量分类文档（减少LLM调用次数）
     */
    private List<TopicClassification> classifyBatch(List<Document> documents, List<String> existingTopics) {
        // 如果文档太多，分批处理（避免单次prompt过长）
        int batchSize = 5;
        List<TopicClassification> allResults = new ArrayList<>();

        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
            List<TopicClassification> batchResults = classifyBatchInternal(batch, existingTopics);
            allResults.addAll(batchResults);
        }

        return allResults;
    }

    /**
     * 内部批量分类实现
     */
    private List<TopicClassification> classifyBatchInternal(List<Document> documents, List<String> existingTopics) {
        String prompt = buildBatchPrompt(documents, existingTopics);

        try {
            BatchClassificationResult result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(BatchClassificationResult.class);

            return result != null && result.getClassifications() != null
                    ? result.getClassifications()
                    : createDefaultClassifications(documents.size());
        } catch (Exception e) {
            log.error("批量主题分类失败，使用默认值", e);
            return createDefaultClassifications(documents.size());
        }
    }

    /**
     * 构建批量分类的提示词
     */
    private String buildBatchPrompt(List<Document> documents, List<String> existingTopics) {
        StringBuilder prompt = new StringBuilder();

        if (existingTopics.isEmpty()) {
            prompt.append("""
                    这是知识库的第一批文档。请为每个文档分析并提取主题信息。

                    """);
        } else {
            prompt.append("""
                    知识库已有以下主题分类：
                    %s

                    请为每个新文档判断：
                    1. 如果内容属于已有主题，选择最匹配的主题
                    2. 如果内容是新主题，提出新的主题名称（2-4个字）

                    """.formatted(String.join("、", existingTopics)));
        }

        prompt.append("请分析以下文档：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String keywords = (String) doc.getMetadata().get(EXCERPT_KEYWORDS);
            String summary = (String) doc.getMetadata().get(SECTION_SUMMARY);

            prompt.append(String.format("""
                    【文档%d】
                    关键词：%s
                    摘要：%s

                    """, i + 1,
                    keywords != null ? keywords : "无",
                    summary != null ? truncate(summary, 200) : "无"));
        }

        prompt.append("""

                请为每个文档提取以下信息：
                - primaryTopic: 主要主题（2-4个字，简洁专业）
                - subTopics: 次要主题列表（最多2个，可为空）
                - questionType: 内容类型（理论知识/实践技巧/案例分析/问答Q&A/其他）

                返回格式：包含 classifications 数组的JSON对象，按文档顺序返回。
                """);

        return prompt.toString();
    }

    /**
     * 创建默认分类结果
     */
    private List<TopicClassification> createDefaultClassifications(int count) {
        List<TopicClassification> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TopicClassification classification = new TopicClassification();
            classification.setPrimaryTopic("未分类");
            classification.setQuestionType("其他");
            results.add(classification);
        }
        return results;
    }

    /**
     * 截断过长的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 单个文档的主题分类结果
     */
    @Data
    public static class TopicClassification {
        private String primaryTopic;
        private List<String> subTopics;
        private String questionType;
    }

    /**
     * 批量分类结果
     */
    @Data
    public static class BatchClassificationResult {
        private List<TopicClassification> classifications;
    }
}
