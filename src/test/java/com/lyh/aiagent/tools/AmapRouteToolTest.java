package com.lyh.aiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapRouteToolTest {

    @Test
    void shouldParseJsonPayloadFromMcpContentArray() {
        TestableAmapRouteTool tool = new TestableAmapRouteTool();

        JsonNode jsonNode = tool.exposeExtractJsonPayload("""
                [{"type":"text","text":"{\\"pois\\":[{\\"id\\":\\"B001\\",\\"name\\":\\"东方明珠\\"}]}"}]
                """);

        assertEquals("B001", jsonNode.path("pois").get(0).path("id").asText());
        assertEquals("东方明珠", jsonNode.path("pois").get(0).path("name").asText());
    }

    @Test
    void shouldNormalizePrefixedMcpToolName() {
        TestableAmapRouteTool tool = new TestableAmapRouteTool();

        assertEquals("maps_text_search",
                tool.exposeNormalizeMapToolName("spring_ai_mcp_client_amap_maps_maps_text_search"));
        assertEquals("maps_direction_walking",
                tool.exposeNormalizeMapToolName("spring_ai_mcp_client_amap_maps_maps_direction_walking"));
        assertEquals(null,
                tool.exposeNormalizeMapToolName("spring_ai_mcp_client_filesystem_read_file"));
    }

    @Test
    void shouldPlanWalkingRouteByDelegatingToAmapMcpTools() {
        TestableAmapRouteTool tool = new TestableAmapRouteTool();
        tool.stub("maps_text_search", """
                {"pois":[{"id":"poi-oriental","name":"上海市东方明珠","address":"世纪大道1号"}]}
                """);
        tool.stub("maps_search_detail:poi-oriental", """
                {"id":"poi-oriental","name":"上海市东方明珠","location":"121.499809,31.239666","address":"世纪大道1号","city":"上海市"}
                """);
        tool.stub("maps_search_detail:poi-bund", """
                {"id":"poi-bund","name":"外滩","location":"121.490317,31.241701","address":"中山东一路","city":"上海市"}
                """);
        tool.stub("maps_text_search:外滩", """
                {"pois":[{"id":"poi-bund","name":"外滩","address":"中山东一路"}]}
                """);
        tool.stub("maps_direction_walking", """
                {"route":{"origin":"121.499809,31.239666","destination":"121.490317,31.241701","paths":[{"distance":"1800","duration":"1500","steps":[{"instruction":"沿滨江大道步行 800 米"},{"instruction":"右转进入中山东一路步行 1000 米"}]}]}}
                """);

        String result = tool.planRouteWithAmap("使用高德地图工具，帮我查一下“上海市东方明珠”的经纬度坐标，并规划一条从东方明珠到外滩的步行路线。");

        assertTrue(result.contains("坐标信息"));
        assertTrue(result.contains("121.499809,31.239666"));
        assertTrue(result.contains("路线信息"));
        assertTrue(result.contains("方式：步行"));
        assertTrue(result.contains("沿滨江大道步行 800 米"));
        assertTrue(tool.invocations().contains("maps_direction_walking"));
        assertTrue(tool.invocations().contains("maps_text_search"));
        assertTrue(tool.invocations().contains("maps_search_detail"));
    }

    @Test
    void shouldRecognizeImplicitWalkingRouteWithoutFromKeyword() {
        TestableAmapRouteTool tool = new TestableAmapRouteTool();
        stubCommonLocations(tool);
        tool.stub("maps_direction_walking", """
                {"route":{"origin":"121.499809,31.239666","destination":"121.490317,31.241701","paths":[{"distance":"1800","duration":"1500","steps":[{"instruction":"沿滨江大道步行 800 米"},{"instruction":"右转进入中山东一路步行 1000 米"}]}]}}
                """);

        String result = assertDoesNotThrow(() -> tool.planRouteWithAmap("东方明珠到外滩步行路线"));

        assertTrue(result.contains("方式：步行"));
        assertTrue(tool.invocations().contains("maps_direction_walking"));
    }

    @Test
    void shouldRecognizeImplicitTransitRouteWithGoPhrase() {
        TestableAmapRouteTool tool = new TestableAmapRouteTool();
        stubCommonLocations(tool);
        tool.stub("maps_direction_transit_integrated", """
                {"route":{"distance":"3200","transits":[{"duration":"1800","walking_distance":"500","segments":[{"walking":{"distance":"200"}},{"bus":{"buslines":[{"name":"地铁2号线","departure_stop":{"name":"陆家嘴"},"arrival_stop":{"name":"南京东路"}}]}}]}]}}
                """);

        String result = assertDoesNotThrow(() -> tool.planRouteWithAmap("东方明珠去外滩怎么走"));

        assertTrue(result.contains("路线信息"));
        assertTrue(result.contains("方式：公交/地铁"));
        assertTrue(tool.invocations().contains("maps_direction_transit_integrated"));
    }

    private void stubCommonLocations(TestableAmapRouteTool tool) {
        tool.stub("maps_text_search", """
                {"pois":[{"id":"poi-oriental","name":"上海市东方明珠","address":"世纪大道1号"}]}
                """);
        tool.stub("maps_search_detail:poi-oriental", """
                {"id":"poi-oriental","name":"上海市东方明珠","location":"121.499809,31.239666","address":"世纪大道1号","city":"上海市"}
                """);
        tool.stub("maps_search_detail:poi-bund", """
                {"id":"poi-bund","name":"外滩","location":"121.490317,31.241701","address":"中山东一路","city":"上海市"}
                """);
        tool.stub("maps_text_search:外滩", """
                {"pois":[{"id":"poi-bund","name":"外滩","address":"中山东一路"}]}
                """);
    }

    private static final class TestableAmapRouteTool extends AmapRouteTool {

        private final Map<String, String> responses = new LinkedHashMap<>();
        private final List<String> invocations = new ArrayList<>();

        private TestableAmapRouteTool() {
            super(new EmptyObjectProvider<>(), new EmptyObjectProvider<>(), new ObjectMapper());
        }

        @Override
        protected Map<String, FunctionCallback> resolveMapCallbacks() {
            return Map.of(
                    "maps_text_search", callback("maps_text_search"),
                    "maps_search_detail", callback("maps_search_detail"),
                    "maps_direction_walking", callback("maps_direction_walking"),
                    "maps_direction_transit_integrated", callback("maps_direction_transit_integrated")
            );
        }

        @Override
        protected JsonNode callMapTool(Map<String, FunctionCallback> mapCallbacks,
                                       String toolName,
                                       Map<String, Object> arguments) {
            try {
                invocations.add(toolName);
                String key = toolName;
                if ("maps_search_detail".equals(toolName)) {
                    key = toolName + ":" + arguments.get("id");
                } else if ("maps_text_search".equals(toolName) && "外滩".equals(arguments.get("keywords"))) {
                    key = toolName + ":外滩";
                }
                return exposeExtractJsonPayload(responses.get(key));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private FunctionCallback callback(String name) {
            return new FunctionCallback() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return name;
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

        private void stub(String key, String response) {
            responses.put(key, response);
        }

        private List<String> invocations() {
            return invocations;
        }

        private JsonNode exposeExtractJsonPayload(String rawResponse) {
            return extractJsonPayload(rawResponse);
        }

        private String exposeNormalizeMapToolName(String rawName) {
            return normalizeMapToolName(rawName);
        }
    }

    private static final class EmptyObjectProvider<T> implements ObjectProvider<T> {

        @Override
        public T getObject(Object... args) {
            return null;
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }

        @Override
        public Stream<T> stream() {
            return Stream.empty();
        }
    }
}
