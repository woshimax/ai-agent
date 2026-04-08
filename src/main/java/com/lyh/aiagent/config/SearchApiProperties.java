package com.lyh.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aiagent.searchapi")
public class SearchApiProperties {

    /**
     * SearchApi 基础地址。
     */
    private String baseUrl = "https://www.searchapi.io/api/v1";

    /**
     * SearchApi API Key。
     */
    private String apiKey;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
