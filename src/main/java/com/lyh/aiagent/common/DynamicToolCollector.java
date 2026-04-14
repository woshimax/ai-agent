package com.lyh.aiagent.common;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

public final class DynamicToolCollector {

    private DynamicToolCollector() {
    }

    public static List<Object> collect(ToolCallback[] localTools,
                                       List<ToolCallbackProvider> toolCallbackProviders,
                                       List<McpSyncClient> syncClients,
                                       List<McpAsyncClient> asyncClients) {
        List<Object> mergedTools = new ArrayList<>(List.of(localTools));

        if (toolCallbackProviders != null) {
            toolCallbackProviders.stream()
                    .filter(provider -> !isSpringMcpProvider(provider))
                    .forEach(mergedTools::add);
        }

        if (syncClients != null && !syncClients.isEmpty()) {
            mergedTools.add(new SyncMcpToolCallbackProvider(syncClients));
        }

        if (asyncClients != null && !asyncClients.isEmpty()) {
            mergedTools.add(new AsyncMcpToolCallbackProvider(asyncClients));
        }

        return mergedTools;
    }

    private static boolean isSpringMcpProvider(ToolCallbackProvider provider) {
        String className = provider.getClass().getName();
        return className.startsWith("org.springframework.ai.mcp.");
    }
}
