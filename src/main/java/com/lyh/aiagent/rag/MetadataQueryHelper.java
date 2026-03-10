package com.lyh.aiagent.rag;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.lyh.aiagent.rag.DocumentMetadataSchema.*;

/**
 * 元数据查询助手
 * 提供各种元数据统计和查询功能
 */
@Slf4j
@Component
public class MetadataQueryHelper {

    private final JdbcTemplate pgJdbcTemplate;

    public MetadataQueryHelper(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate) {
        this.pgJdbcTemplate = pgJdbcTemplate;
    }

    /**
     * 获取所有主题的分布统计
     */
    public List<TopicStat> getTopicDistribution() {
        String sql = """
                SELECT
                    metadata->>'topic' as topic,
                    COUNT(*) as count
                FROM vector_store
                WHERE metadata->>'topic' IS NOT NULL
                GROUP BY metadata->>'topic'
                ORDER BY count DESC
                """;

        return pgJdbcTemplate.query(sql, (rs, rowNum) -> {
            TopicStat stat = new TopicStat();
            stat.setTopic(rs.getString("topic"));
            stat.setCount(rs.getInt("count"));
            return stat;
        });
    }

    /**
     * 获取所有问题类型的分布
     */
    public List<QuestionTypeStat> getQuestionTypeDistribution() {
        String sql = """
                SELECT
                    metadata->>'question_type' as question_type,
                    COUNT(*) as count
                FROM vector_store
                WHERE metadata->>'question_type' IS NOT NULL
                GROUP BY metadata->>'question_type'
                ORDER BY count DESC
                """;

        return pgJdbcTemplate.query(sql, (rs, rowNum) -> {
            QuestionTypeStat stat = new QuestionTypeStat();
            stat.setQuestionType(rs.getString("question_type"));
            stat.setCount(rs.getInt("count"));
            return stat;
        });
    }

    /**
     * 获取最近导入的文档
     */
    public List<Map<String, Object>> getRecentImports(int limit) {
        String sql = """
                SELECT
                    metadata->>'source' as source,
                    metadata->>'topic' as topic,
                    metadata->>'import_time' as import_time,
                    COUNT(*) as chunks
                FROM vector_store
                WHERE metadata->>'import_time' IS NOT NULL
                GROUP BY metadata->>'source', metadata->>'topic', metadata->>'import_time'
                ORDER BY metadata->>'import_time' DESC
                LIMIT ?
                """;

        return pgJdbcTemplate.queryForList(sql, limit);
    }

    /**
     * 按主题查询文档的关键词词云
     */
    public List<String> getKeywordsByTopic(String topic, int limit) {
        String sql = """
                SELECT metadata->>'excerpt_keywords' as keywords
                FROM vector_store
                WHERE metadata->>'topic' = ?
                AND metadata->>'excerpt_keywords' IS NOT NULL
                LIMIT ?
                """;

        List<String> keywordsList = pgJdbcTemplate.queryForList(sql, String.class, topic, limit);

        // 合并所有关键词
        return keywordsList.stream()
                .flatMap(keywords -> List.of(keywords.split(",")).stream())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 打印知识库统计信息
     */
    public void printStatistics() {
        log.info("=== 知识库元数据统计 ===");

        // 总文档数
        Integer totalChunks = pgJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store",
                Integer.class
        );
        log.info("总分块数: {}", totalChunks);

        // 主题分布
        log.info("\n主题分布:");
        List<TopicStat> topics = getTopicDistribution();
        topics.forEach(stat -> log.info("  {} : {} 个文档", stat.getTopic(), stat.getCount()));

        // 问题类型分布
        log.info("\n问题类型分布:");
        List<QuestionTypeStat> types = getQuestionTypeDistribution();
        types.forEach(stat -> log.info("  {} : {} 个文档", stat.getQuestionType(), stat.getCount()));

        // 最近导入
        log.info("\n最近导入:");
        List<Map<String, Object>> recent = getRecentImports(5);
        recent.forEach(item -> log.info("  {} - {} ({} 个分块)",
                item.get("source"), item.get("topic"), item.get("chunks")));
    }

    @Data
    public static class TopicStat {
        private String topic;
        private Integer count;
    }

    @Data
    public static class QuestionTypeStat {
        private String questionType;
        private Integer count;
    }
}
