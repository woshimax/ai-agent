package com.lyh.aiagent.agent;

import com.lyh.aiagent.common.DynamicToolCollector;
import com.lyh.aiagent.common.ToolCallbackResolver;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manus Agent 工厂类
 * 用于创建 Manus 实例，避免单例模式下的状态污染问题
 */
@Component
@Lazy // 延迟初始化，等待 MCP 服务器启动完成
@Slf4j
public class ManusFactory {

    private Object[] allTools;
    private final ChatModel dashscopeChatModel;
    private final ChatMemory manusChatMemory;
    private final ToolCallback[] aiAgentTools;

    @Autowired(required = false)
    private List<ToolCallbackProvider> toolCallbackProviders;

    @Autowired
    private ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider;

    @Autowired
    private ObjectProvider<List<McpAsyncClient>> mcpAsyncClientsProvider;

    private List<String> lastResolvedToolNames = List.of();

    /**
     * 构造函数
     * 使用 @Qualifier 指定注入 dashscopeChatModel
     */
    public ManusFactory(ToolCallback[] aiAgentTools,
                        @Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
                        @Qualifier("manusChatMemory") ChatMemory manusChatMemory) {
        this.aiAgentTools = aiAgentTools;
        this.dashscopeChatModel = dashscopeChatModel;
        this.manusChatMemory = manusChatMemory;
        log.info("ManusFactory 已创建，等待首次调用时初始化工具列表");
    }

    /**
     * 刷新工具列表（在 create 前执行，避免 MCP 异步启动导致工具快照过早）
     */
    private synchronized void refreshTools() {
        List<McpSyncClient> syncClients = mcpSyncClientsProvider.getIfAvailable(() -> List.of());
        List<McpAsyncClient> asyncClients = mcpAsyncClientsProvider.getIfAvailable(() -> List.of());
        log.info("Manus MCP 客户端状态: sync={}, async={}, providers={}",
                syncClients.size(),
                asyncClients.size(),
                toolCallbackProviders == null ? 0 : toolCallbackProviders.size());

        List<Object> mergedTools = DynamicToolCollector.collect(aiAgentTools, toolCallbackProviders, syncClients, asyncClients);

        List<FunctionCallback> resolvedTools = ToolCallbackResolver.resolve(mergedTools.toArray());
        this.allTools = resolvedTools.toArray(FunctionCallback[]::new);

        List<String> toolNames = resolvedTools.stream()
                .map(FunctionCallback::getName)
                .toList();

        boolean toolSetChanged = !Objects.equals(this.lastResolvedToolNames, toolNames);
        if (toolSetChanged) {
            log.info("===== Manus 可用工具已刷新 =====");
            log.info("工具总数: {}", toolNames.size());
            log.info("工具列表: {}", String.join(", ", toolNames));
            log.info("==============================");
            this.lastResolvedToolNames = toolNames;
        }
        if (toolNames.stream().noneMatch(name -> name != null && name.startsWith("maps_"))) {
            log.warn("当前 Manus 工具列表中未发现高德地图 MCP 工具（maps_*）");
        }
    }

    /**
     * 创建新的 Manus 实例
     * 每次调用都会创建全新的实例，确保状态隔离
     *
     * @return 新的 Manus 实例
     */
    public Manus create() {
        return create(null);
    }

    public Manus create(String conversationId) {
        refreshTools();

        Manus manus = new Manus(allTools, dashscopeChatModel);
        manus.setChatMemory(manusChatMemory);
        manus.setConversationId(conversationId);
        return manus;
    }
}
