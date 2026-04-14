package com.lyh.aiagent.common;

import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolCallbackResolver {

    private ToolCallbackResolver() {
    }

    public static List<FunctionCallback> resolve(Object... tools) {
        Map<String, FunctionCallback> callbacks = new LinkedHashMap<>();
        if (tools == null) {
            return new ArrayList<>();
        }

        for (Object tool : tools) {
            if (tool == null) {
                continue;
            }
            if (tool instanceof ToolCallbackProvider provider) {
                Arrays.stream(provider.getToolCallbacks()).forEach(callback -> addCallback(callbacks, callback));
                continue;
            }
            if (tool instanceof ToolCallback toolCallback) {
                addCallback(callbacks, toolCallback);
                continue;
            }
            if (tool instanceof FunctionCallback functionCallback) {
                addCallback(callbacks, functionCallback);
                continue;
            }
            Arrays.stream(ToolCallbacks.from(tool)).forEach(callback -> addCallback(callbacks, callback));
        }

        return new ArrayList<>(callbacks.values());
    }

    public static FunctionCallback[] resolveToArray(Object... tools) {
        return resolve(tools).toArray(FunctionCallback[]::new);
    }

    private static void addCallback(Map<String, FunctionCallback> callbacks, FunctionCallback callback) {
        if (callback == null) {
            return;
        }
        String name = callback.getName();
        if (!StringUtils.hasText(name)) {
            name = callback.getClass().getName() + "@" + System.identityHashCode(callback);
        }
        callbacks.putIfAbsent(name, callback);
    }
}
