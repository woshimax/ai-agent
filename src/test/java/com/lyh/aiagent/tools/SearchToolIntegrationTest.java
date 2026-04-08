package com.lyh.aiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.lyh.aiagent.config.SearchApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SearchToolIntegrationTest.TestConfig.class)
class SearchToolIntegrationTest {

    @Autowired
    private SearchTool searchTool;

    @Autowired
    private SearchApiProperties searchApiProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldSearchProgrammingNavigationWithRealNetwork() throws Exception {
        String result = searchTool.searchWeb("编程导航", null, null, null, null, null);
        System.out.println("SearchTool real result:\n" + result);
        JsonNode json = objectMapper.readTree(result);

        assertFalse(searchApiProperties.getApiKey() == null || searchApiProperties.getApiKey().isBlank());
        assertEquals("编程导航", json.path("query").asText());
        assertTrue(json.path("organic_results").isArray());
        assertTrue(json.path("organic_results").size() > 0);
        assertTrue(json.path("organic_results").get(0).path("title").asText().contains("编程导航"));
        assertTrue(json.path("organic_results").get(0).path("link").asText().startsWith("http"));
    }

    @Configuration
    @EnableConfigurationProperties(SearchApiProperties.class)
    static class TestConfig {

        @Bean
        SearchTool searchTool(SearchApiProperties searchApiProperties, ObjectMapper objectMapper) {
            return new SearchTool(RestClient.builder(), searchApiProperties, objectMapper);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
