package com.lyh.aiagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallbackResolverTest {

    @Test
    void shouldResolveCallbacksFromToolCallbackProvider() {
        FunctionCallback directCallback = callback("searchWeb");
        FunctionCallback providerCallback = callback("maps_route");

        ToolCallbackProvider provider = ToolCallbackProvider.from(providerCallback);

        List<FunctionCallback> callbacks = ToolCallbackResolver.resolve(directCallback, provider);

        assertEquals(List.of("searchWeb", "maps_route"),
                callbacks.stream().map(FunctionCallback::getName).toList());
    }

    private FunctionCallback callback(String name) {
        return new FunctionCallback() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name + " description";
            }

            @Override
            public String getInputTypeSchema() {
                return "{}";
            }

            @Override
            public String call(String functionInput) {
                return functionInput;
            }
        };
    }
}
