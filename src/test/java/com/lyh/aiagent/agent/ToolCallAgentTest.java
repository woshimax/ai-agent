package com.lyh.aiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallAgentTest {

    @Test
    void shouldStripTerminateToolCallFromDisplayedResponse() {
        ExposedToolCallAgent agent = new ExposedToolCallAgent();
        String rawResponse = """
                根据中国气象局的最新天气预报，今天北京阴转小雨。

                doTerminate(finalMessage: "今天北京天气：阴转小雨，气温9℃~18℃，建议携带雨具。")
                """;

        String normalized = agent.normalize(rawResponse);

        assertEquals("根据中国气象局的最新天气预报，今天北京阴转小雨。", normalized);
    }

    @Test
    void shouldExtractFinalMessageWhenResponseOnlyContainsTerminateToolCall() {
        ExposedToolCallAgent agent = new ExposedToolCallAgent();
        String rawResponse = "doTerminate(finalMessage: \"今天北京天气：阴转小雨，气温9℃~18℃，建议携带雨具。\")";

        String normalized = agent.normalize(rawResponse);

        assertEquals("今天北京天气：阴转小雨，气温9℃~18℃，建议携带雨具。", normalized);
    }

    @Test
    void shouldExtractTerminateArg0Json() {
        ExposedToolCallAgent agent = new ExposedToolCallAgent();
        String finalMessage = agent.extractFromArgs("{\"arg0\":\"合肥约会场所推荐指南\"}");

        assertEquals("合肥约会场所推荐指南", finalMessage);
    }

    @Test
    void shouldExtractFinalMessageFromToolResponse() {
        ExposedToolCallAgent agent = new ExposedToolCallAgent();
        String response = agent.extractFromToolResponse("\"任务已终止，最终回复：合肥约会场所推荐指南\\n\\n第一部分\"");

        assertEquals("合肥约会场所推荐指南\n\n第一部分", response);
    }

    private static class ExposedToolCallAgent extends ToolCallAgent {
        ExposedToolCallAgent() {
            super(new ToolCallback[0]);
        }

        String normalize(String responseText) {
            return normalizeFinalResponse(responseText);
        }

        String extractFromArgs(String args) {
            return extractFinalMessage(args);
        }

        String extractFromToolResponse(String responseText) {
            return extractFinalMessageFromToolResponse(responseText);
        }
    }
}
