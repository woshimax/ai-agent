package com.lyh.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aiagent.dashscope")
public class DashscopeProperties {
    /**
     * DashScope / 百炼 API Key。
     * 建议通过环境变量 DASHSCOPE_API_KEY 注入，避免提交到代码仓库。
     */
    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}

