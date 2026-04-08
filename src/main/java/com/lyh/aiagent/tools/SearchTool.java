package com.lyh.aiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyh.aiagent.config.SearchApiProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchTool {

    private static final int MAX_ORGANIC_RESULTS = 5;
    private static final int MAX_RELATED_QUESTIONS = 3;

    private final RestClient restClient;
    private final SearchApiProperties properties;
    private final ObjectMapper objectMapper;

    public SearchTool(RestClient.Builder restClientBuilder,
                      SearchApiProperties properties,
                      ObjectMapper objectMapper) {
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "联网搜索网页信息，返回搜索摘要、直接答案和前几个自然搜索结果。适合查找最新信息、新闻、网页资料。")
    public String searchWeb(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "搜索地点，例如 Beijing, China", required = false) String location,
            @ToolParam(description = "国家代码，例如 us、cn", required = false) String gl,
            @ToolParam(description = "界面语言，例如 en、zh-cn", required = false) String hl,
            @ToolParam(description = "时间范围，可选值：last_hour、last_day、last_week、last_month、last_year", required = false) String timePeriod,
            @ToolParam(description = "结果页码，从 1 开始", required = false) Integer page) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("未配置 SearchApi API Key");
        }

        String responseBody = restClient.get()
                .uri(uriBuilder -> buildSearchUri(uriBuilder, query, location, gl, hl, timePeriod, page))
                .retrieve()
                .body(String.class);

        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("搜索接口返回空结果");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return formatSearchResult(root);
        } catch (Exception e) {
            throw new IllegalStateException("解析搜索结果失败", e);
        }
    }

    private java.net.URI buildSearchUri(UriBuilder uriBuilder,
                                        String query,
                                        String location,
                                        String gl,
                                        String hl,
                                        String timePeriod,
                                        Integer page) {
        UriBuilder builder = uriBuilder
                .path("/search")
                .queryParam("engine", "google")
                .queryParam("api_key", properties.getApiKey())
                .queryParam("q", query)
                .queryParam("gl", StringUtils.hasText(gl) ? gl : "us")
                .queryParam("hl", StringUtils.hasText(hl) ? hl : "en")
                .queryParam("page", page != null && page > 0 ? page : 1);

        if (StringUtils.hasText(location)) {
            builder.queryParam("location", location);
        }
        if (StringUtils.hasText(timePeriod)) {
            builder.queryParam("time_period", timePeriod);
        }
        return builder.build();
    }

    private String formatSearchResult(JsonNode root) {
        ObjectNode result = objectMapper.createObjectNode();

        putIfHasText(result, "query", text(root.at("/search_information/query_displayed")));
        putIfHasText(result, "answer", text(root.at("/answer_box/answer")));

        String knowledgeTitle = text(root.at("/knowledge_graph/title"));
        String knowledgeDescription = text(root.at("/knowledge_graph/description"));
        if (StringUtils.hasText(knowledgeTitle) || StringUtils.hasText(knowledgeDescription)) {
            ObjectNode knowledgeGraph = objectMapper.createObjectNode();
            putIfHasText(knowledgeGraph, "title", knowledgeTitle);
            putIfHasText(knowledgeGraph, "description", knowledgeDescription);
            result.set("knowledge_graph", knowledgeGraph);
        }

        ArrayNode organicResults = objectMapper.createArrayNode();
        JsonNode rawOrganicResults = root.path("organic_results");
        if (rawOrganicResults.isArray()) {
            for (int i = 0; i < Math.min(rawOrganicResults.size(), MAX_ORGANIC_RESULTS); i++) {
                JsonNode item = rawOrganicResults.get(i);
                ObjectNode organicResult = objectMapper.createObjectNode();
                putIfHasText(organicResult, "position", text(item.path("position")));
                putIfHasText(organicResult, "title", text(item.path("title")));
                putIfHasText(organicResult, "link", text(item.path("link")));
                putIfHasText(organicResult, "displayed_link", text(item.path("displayed_link")));
                putIfHasText(organicResult, "snippet", text(item.path("snippet")));
                putIfHasText(organicResult, "source", text(item.path("source")));
                putIfHasText(organicResult, "date", text(item.path("date")));
                putIfHasText(organicResult, "thumbnail", text(item.path("thumbnail")));
                if (!organicResult.isEmpty()) {
                    organicResults.add(organicResult);
                }
            }
        }
        if (!organicResults.isEmpty()) {
            result.set("organic_results", organicResults);
        }

        ArrayNode relatedQuestions = objectMapper.createArrayNode();
        JsonNode rawRelatedQuestions = root.path("related_questions");
        if (rawRelatedQuestions.isArray()) {
            for (int i = 0; i < Math.min(rawRelatedQuestions.size(), MAX_RELATED_QUESTIONS); i++) {
                JsonNode item = rawRelatedQuestions.get(i);
                ObjectNode question = objectMapper.createObjectNode();
                putIfHasText(question, "question", text(item.path("question")));
                putIfHasText(question, "answer", text(item.path("answer")));
                if (!question.isEmpty()) {
                    relatedQuestions.add(question);
                }
            }
        }
        if (!relatedQuestions.isEmpty()) {
            result.set("related_questions", relatedQuestions);
        }

        if (result.isEmpty()) {
            result.put("message", "未获取到可用搜索结果");
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("生成搜索结果失败", e);
        }
    }

    private void putIfHasText(ObjectNode node, String fieldName, String value) {
        if (StringUtils.hasText(value)) {
            node.put(fieldName, value);
        }
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
