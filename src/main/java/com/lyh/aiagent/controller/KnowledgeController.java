package com.lyh.aiagent.controller;

import com.lyh.aiagent.rag.KnowledgeImportService;
import com.lyh.aiagent.rag.MetadataQueryHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@CrossOrigin
public class KnowledgeController {

    private final KnowledgeImportService knowledgeImportService;
    private final MetadataQueryHelper metadataQueryHelper;

    /**
     * 手动触发导入新文件
     */
    @PostMapping("/import")
    public Map<String, Object> importNewFiles() {
        log.info("手动触发知识库导入");
        try {
            knowledgeImportService.importNewFiles();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "导入完成");
            return result;
        } catch (Exception e) {
            log.error("导入失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 查看元数据统计
     */
    @GetMapping("/stats")
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 主题分布
        List<MetadataQueryHelper.TopicStat> topics = metadataQueryHelper.getTopicDistribution();
        stats.put("topics", topics);

        // 问题类型分布
        List<MetadataQueryHelper.QuestionTypeStat> questionTypes =
            metadataQueryHelper.getQuestionTypeDistribution();
        stats.put("questionTypes", questionTypes);

        // 最近导入
        List<Map<String, Object>> recentImports = metadataQueryHelper.getRecentImports(10);
        stats.put("recentImports", recentImports);

        return stats;
    }

    /**
     * 查询某个主题的关键词
     */
    @GetMapping("/keywords/{topic}")
    public Map<String, Object> getKeywordsByTopic(@PathVariable String topic) {
        List<String> keywords = metadataQueryHelper.getKeywordsByTopic(topic, 50);
        Map<String, Object> result = new HashMap<>();
        result.put("topic", topic);
        result.put("keywords", keywords);
        return result;
    }

    /**
     * 打印统计信息到控制台
     */
    @PostMapping("/stats/print")
    public Map<String, Object> printStatistics() {
        metadataQueryHelper.printStatistics();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "统计信息已打印到控制台");
        return result;
    }
}
