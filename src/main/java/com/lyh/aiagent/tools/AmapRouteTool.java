package com.lyh.aiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.lyh.aiagent.common.ToolCallbackResolver;

@Component
@Slf4j
public class AmapRouteTool {

    private static final String ROUTE_TOOL_NAME = "planRouteWithAmap";
    private static final List<String> SUPPORTED_MAP_TOOL_NAMES = List.of(
            "maps_regeocode",
            "maps_geo",
            "maps_ip_location",
            "maps_weather",
            "maps_search_detail",
            "maps_bicycling",
            "maps_direction_walking",
            "maps_direction_driving",
            "maps_direction_transit_integrated",
            "maps_distance",
            "maps_text_search",
            "maps_around_search"
    );
    private static final List<String> ROUTE_CONNECTORS = List.of("前往", "到", "去");
    private static final List<String> DESTINATION_SEPARATORS = List.of(
            "的步行路线", "的骑行路线", "的驾车路线", "的公交路线", "的地铁路线", "的路线", "的导航",
            "怎么走", "怎么去", "如何去", "步行路线", "骑行路线", "驾车路线", "公交路线", "地铁路线",
            "步行", "骑行", "驾车", "自驾", "打车", "公交", "地铁", "乘车",
            "路线", "导航", "。", "，", ",", "？", "?"
    );
    private static final List<String> REQUEST_PREFIX_MARKERS = List.of(
            "帮我查一下", "帮我查", "帮我搜一下", "帮我搜", "帮我看一下", "帮我看",
            "帮我规划一下", "帮我规划", "请帮我查一下", "请帮我规划一下", "请帮我规划",
            "请帮我", "麻烦帮我", "麻烦", "使用高德地图工具", "用高德地图工具",
            "用高德地图", "高德地图工具", "查一下", "查询一下", "查询", "搜索一下",
            "搜索", "规划一条", "规划一下", "规划", "导航一下", "导航", "告诉我",
            "看一下", "看下"
    );

    private final ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider;
    private final ObjectProvider<List<McpAsyncClient>> mcpAsyncClientsProvider;
    private final ObjectMapper objectMapper;

    public AmapRouteTool(ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider,
                         ObjectProvider<List<McpAsyncClient>> mcpAsyncClientsProvider,
                         ObjectMapper objectMapper) {
        this.mcpSyncClientsProvider = mcpSyncClientsProvider;
        this.mcpAsyncClientsProvider = mcpAsyncClientsProvider;
        this.objectMapper = objectMapper;
    }

    @Tool(name = ROUTE_TOOL_NAME, description = """
            使用高德地图完成地点经纬度查询、地点解析和路线规划。
            当用户询问“从哪里到哪里怎么走 / 路线 / 导航 / 前往某地 / 查询某地点坐标 / 经纬度”时，优先调用此工具。
            调用时请直接把用户原始问题完整传入 request，本工具会自动调用高德 maps_* MCP 工具完成查询。
            """)
    public String planRouteWithAmap(
            @ToolParam(description = "用户关于坐标或路线的原始问题，尽量原样传入") String request) {
        if (!StringUtils.hasText(request)) {
            throw new IllegalArgumentException("request 不能为空");
        }
        log.info("AmapRouteTool 收到请求: {}", abbreviate(request, 200));

        Map<String, FunctionCallback> mapCallbacks = resolveMapCallbacks();
        if (mapCallbacks.isEmpty()) {
            throw new IllegalStateException("当前未发现可用的高德地图 MCP 工具");
        }
        log.info("AmapRouteTool 已识别高德工具: {}", String.join(", ", mapCallbacks.keySet()));

        RouteRequest routeRequest = parseRequest(request);
        if (!routeRequest.hasRoute() && !routeRequest.coordinatesRequested()) {
            throw new IllegalArgumentException("当前请求未识别出坐标查询或路线规划意图");
        }

        LocationInfo coordinateTarget = null;
        if (routeRequest.coordinatesRequested() && StringUtils.hasText(routeRequest.coordinateTarget())) {
            coordinateTarget = resolveLocation(mapCallbacks, routeRequest.coordinateTarget(), null);
        }

        LocationInfo origin = null;
        LocationInfo destination = null;
        if (routeRequest.hasRoute()) {
            origin = resolveLocation(mapCallbacks, routeRequest.origin(), null);
            destination = resolveLocation(mapCallbacks, routeRequest.destination(), origin.city());
        }

        StringBuilder result = new StringBuilder("已调用高德地图完成查询。\n");
        if (coordinateTarget != null) {
            appendCoordinateSummary(result, coordinateTarget);
        }
        if (origin != null && destination != null) {
            JsonNode routeResult = planRoute(mapCallbacks, routeRequest.mode(), origin, destination);
            appendRouteSummary(result, routeRequest.mode(), origin, destination, routeResult);
        }
        return result.toString().trim();
    }

    protected Map<String, FunctionCallback> resolveMapCallbacks() {
        List<Object> providers = new ArrayList<>();
        List<McpSyncClient> syncClients = mcpSyncClientsProvider.getIfAvailable(() -> List.of());
        List<McpAsyncClient> asyncClients = mcpAsyncClientsProvider.getIfAvailable(() -> List.of());
        if (syncClients != null && !syncClients.isEmpty()) {
            providers.add(new SyncMcpToolCallbackProvider(syncClients));
        }
        if (asyncClients != null && !asyncClients.isEmpty()) {
            providers.add(new AsyncMcpToolCallbackProvider(asyncClients));
        }
        Map<String, FunctionCallback> callbacks = new LinkedHashMap<>();
        for (FunctionCallback callback : ToolCallbackResolver.resolve(providers.toArray())) {
            String normalizedName = normalizeMapToolName(callback.getName());
            if (normalizedName != null) {
                callbacks.putIfAbsent(normalizedName, callback);
            }
        }
        return callbacks;
    }

    protected String normalizeMapToolName(String callbackName) {
        if (!StringUtils.hasText(callbackName)) {
            return null;
        }
        if (SUPPORTED_MAP_TOOL_NAMES.contains(callbackName)) {
            return callbackName;
        }
        int index = callbackName.lastIndexOf("maps_");
        if (index < 0) {
            return null;
        }
        String normalized = callbackName.substring(index);
        return SUPPORTED_MAP_TOOL_NAMES.contains(normalized) ? normalized : null;
    }

    protected JsonNode callMapTool(Map<String, FunctionCallback> mapCallbacks,
                                   String toolName,
                                   Map<String, Object> arguments) {
        FunctionCallback callback = mapCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("未找到高德工具: " + toolName);
        }
        try {
            String requestJson = objectMapper.writeValueAsString(arguments);
            log.info("AmapRouteTool 调用高德工具: {} args={}", toolName, requestJson);
            String rawResponse = callback.call(requestJson);
            log.info("AmapRouteTool 工具返回: {} => {}", toolName, abbreviate(rawResponse, 400));
            return extractJsonPayload(rawResponse);
        } catch (Exception e) {
            throw new IllegalStateException("调用高德工具失败: " + toolName, e);
        }
    }

    protected JsonNode extractJsonPayload(String rawResponse) {
        if (!StringUtils.hasText(rawResponse)) {
            throw new IllegalStateException("高德工具返回空结果");
        }
        JsonNode direct = tryReadTree(rawResponse);
        if (isBusinessPayload(direct)) {
            return direct;
        }
        String textPayload = extractTextPayload(direct);
        if (StringUtils.hasText(textPayload)) {
            JsonNode nested = tryReadTree(textPayload);
            if (nested != null) {
                return nested;
            }
            throw new IllegalStateException(textPayload);
        }
        throw new IllegalStateException(rawResponse);
    }

    private RouteRequest parseRequest(String request) {
        String normalized = normalizeText(request);
        boolean coordinatesRequested = containsAny(normalized, "经纬度", "坐标", "定位");
        TravelMode mode = detectTravelMode(normalized);

        RouteEndpoints endpoints = extractRouteEndpoints(normalized);
        String origin = endpoints.origin();
        String destination = endpoints.destination();

        String coordinateTarget = extractCoordinateTarget(normalized, origin);
        return new RouteRequest(origin, destination, mode, coordinateTarget, coordinatesRequested);
    }

    private RouteEndpoints extractRouteEndpoints(String normalized) {
        RouteEndpoints endpoints = extractExplicitRoute(normalized);
        if (endpoints.complete()) {
            return endpoints;
        }

        endpoints = extractLabeledRoute(normalized);
        if (endpoints.complete()) {
            return endpoints;
        }

        return extractImplicitRoute(normalized);
    }

    private RouteEndpoints extractExplicitRoute(String normalized) {
        int fromIndex = normalized.indexOf("从");
        if (fromIndex < 0) {
            return RouteEndpoints.empty();
        }
        return extractRouteFromSegment(normalized.substring(fromIndex + 1));
    }

    private RouteEndpoints extractLabeledRoute(String normalized) {
        String origin = extractLabeledPlace(normalized,
                List.of("起点：", "起点:", "起点是", "起点为"),
                List.of("终点", "到", "去", "前往", "，", ",", "。", "；", ";"));
        String destination = extractLabeledPlace(normalized,
                List.of("终点：", "终点:", "终点是", "终点为"),
                DESTINATION_SEPARATORS);
        return new RouteEndpoints(origin, destination);
    }

    private RouteEndpoints extractImplicitRoute(String normalized) {
        RouteConnectorMatch connectorMatch = findFirstRouteConnector(normalized);
        if (connectorMatch == null) {
            return RouteEndpoints.empty();
        }
        String origin = cleanupOriginCandidate(normalized.substring(0, connectorMatch.index()));
        String destination = cleanupPlace(trimDestination(
                normalized.substring(connectorMatch.index() + connectorMatch.connector().length())));
        return new RouteEndpoints(origin, destination);
    }

    private RouteEndpoints extractRouteFromSegment(String segment) {
        RouteConnectorMatch connectorMatch = findFirstRouteConnector(segment);
        if (connectorMatch == null) {
            return RouteEndpoints.empty();
        }
        String origin = cleanupPlace(segment.substring(0, connectorMatch.index()));
        String destination = cleanupPlace(trimDestination(
                segment.substring(connectorMatch.index() + connectorMatch.connector().length())));
        return new RouteEndpoints(origin, destination);
    }

    private String extractLabeledPlace(String text, List<String> markers, List<String> terminators) {
        int startIndex = -1;
        int markerLength = 0;
        for (String marker : markers) {
            int index = text.indexOf(marker);
            if (index >= 0 && (startIndex < 0 || index < startIndex)) {
                startIndex = index;
                markerLength = marker.length();
            }
        }
        if (startIndex < 0) {
            return null;
        }

        String candidate = text.substring(startIndex + markerLength).trim();
        int endIndex = -1;
        for (String terminator : terminators) {
            int index = candidate.indexOf(terminator);
            if (index >= 0 && (endIndex < 0 || index < endIndex)) {
                endIndex = index;
            }
        }
        if (endIndex >= 0) {
            candidate = candidate.substring(0, endIndex);
        }
        return cleanupPlace(candidate);
    }

    private RouteConnectorMatch findFirstRouteConnector(String text) {
        int bestIndex = -1;
        String bestConnector = null;
        for (String connector : ROUTE_CONNECTORS) {
            int index = text.indexOf(connector);
            if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
                bestIndex = index;
                bestConnector = connector;
            }
        }
        return bestIndex >= 0 ? new RouteConnectorMatch(bestConnector, bestIndex) : null;
    }

    private String extractCoordinateTarget(String normalized, String fallbackOrigin) {
        if (!containsAny(normalized, "经纬度", "坐标", "定位")) {
            return null;
        }
        List<String> quoted = extractQuotedPhrases(normalized);
        if (!quoted.isEmpty()) {
            return cleanupPlace(quoted.getFirst());
        }

        String[] markers = {"查一下", "查询", "帮我查", "帮我搜", "搜索", "看看"};
        for (String marker : markers) {
            int markerIndex = normalized.indexOf(marker);
            if (markerIndex >= 0) {
                String tail = normalized.substring(markerIndex + marker.length()).trim();
                String candidate = tail;
                for (String separator : List.of("的经纬度", "经纬度", "的坐标", "坐标", "的位置", "定位")) {
                    int separatorIndex = candidate.indexOf(separator);
                    if (separatorIndex >= 0) {
                        candidate = candidate.substring(0, separatorIndex);
                        break;
                    }
                }
                candidate = cleanupPlace(candidate);
                if (StringUtils.hasText(candidate)) {
                    return candidate;
                }
            }
        }
        return cleanupPlace(fallbackOrigin);
    }

    private List<String> extractQuotedPhrases(String text) {
        List<String> values = new ArrayList<>();
        StringBuilder current = null;
        for (char currentChar : text.toCharArray()) {
            if (currentChar == '“' || currentChar == '"' || currentChar == '\'') {
                if (current == null) {
                    current = new StringBuilder();
                } else {
                    String value = cleanupPlace(current.toString());
                    if (StringUtils.hasText(value)) {
                        values.add(value);
                    }
                    current = null;
                }
                continue;
            }
            if (current != null) {
                current.append(currentChar);
            }
        }
        return values;
    }

    private TravelMode detectTravelMode(String request) {
        if (containsAny(request, "步行", "走路", "徒步")) {
            return TravelMode.WALKING;
        }
        if (containsAny(request, "骑行", "骑车", "单车", "自行车")) {
            return TravelMode.BICYCLING;
        }
        if (containsAny(request, "驾车", "自驾", "开车", "打车")) {
            return TravelMode.DRIVING;
        }
        return TravelMode.TRANSIT;
    }

    private LocationInfo resolveLocation(Map<String, FunctionCallback> mapCallbacks, String keyword, String cityHint) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("地点名称不能为空");
        }
        LocationInfo byTextSearch = resolveByTextSearch(mapCallbacks, keyword, cityHint);
        if (byTextSearch != null) {
            return byTextSearch;
        }
        if (StringUtils.hasText(cityHint)) {
            LocationInfo retried = resolveByTextSearch(mapCallbacks, keyword, null);
            if (retried != null) {
                return retried;
            }
        }
        LocationInfo byGeo = resolveByGeo(mapCallbacks, keyword, cityHint);
        if (byGeo != null) {
            return byGeo;
        }
        throw new IllegalStateException("未能解析地点: " + keyword);
    }

    private LocationInfo resolveByTextSearch(Map<String, FunctionCallback> mapCallbacks, String keyword, String cityHint) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("keywords", keyword);
        if (StringUtils.hasText(cityHint)) {
            arguments.put("city", cityHint);
        }
        JsonNode searchResult = callMapTool(mapCallbacks, "maps_text_search", arguments);
        JsonNode pois = searchResult.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return null;
        }
        JsonNode firstPoi = pois.get(0);
        String poiId = text(firstPoi.path("id"));
        if (!StringUtils.hasText(poiId)) {
            return null;
        }
        JsonNode detailResult = callMapTool(mapCallbacks, "maps_search_detail", Map.of("id", poiId));
        String location = text(detailResult.path("location"));
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String name = firstNonBlank(text(detailResult.path("name")), text(firstPoi.path("name")), keyword);
        String address = firstNonBlank(text(detailResult.path("address")), text(firstPoi.path("address")));
        String city = text(detailResult.path("city"));
        return new LocationInfo(keyword, name, location, city, address);
    }

    private LocationInfo resolveByGeo(Map<String, FunctionCallback> mapCallbacks, String keyword, String cityHint) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", keyword);
        if (StringUtils.hasText(cityHint)) {
            arguments.put("city", cityHint);
        }
        JsonNode geoResult = callMapTool(mapCallbacks, "maps_geo", arguments);
        JsonNode geocodes = geoResult.path("return");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            return null;
        }
        JsonNode first = geocodes.get(0);
        String location = text(first.path("location"));
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String city = firstNonBlank(text(first.path("city")), text(first.path("province")));
        String address = joinNonBlank(text(first.path("district")), text(first.path("street")), text(first.path("number")));
        return new LocationInfo(keyword, keyword, location, city, address);
    }

    private JsonNode planRoute(Map<String, FunctionCallback> mapCallbacks,
                               TravelMode mode,
                               LocationInfo origin,
                               LocationInfo destination) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("origin", origin.location());
        arguments.put("destination", destination.location());

        String toolName;
        if (mode == TravelMode.WALKING) {
            toolName = "maps_direction_walking";
        } else if (mode == TravelMode.BICYCLING) {
            toolName = "maps_bicycling";
        } else if (mode == TravelMode.DRIVING) {
            toolName = "maps_direction_driving";
        } else {
            toolName = "maps_direction_transit_integrated";
            arguments.put("city", firstNonBlank(origin.city(), ""));
            arguments.put("cityd", firstNonBlank(destination.city(), ""));
        }
        return callMapTool(mapCallbacks, toolName, arguments);
    }

    private void appendCoordinateSummary(StringBuilder result, LocationInfo locationInfo) {
        result.append("\n坐标信息：\n")
                .append("- 地点：").append(locationInfo.displayName()).append('\n')
                .append("- 坐标：").append(locationInfo.location()).append('\n');
        if (StringUtils.hasText(locationInfo.address())) {
            result.append("- 地址：").append(locationInfo.address()).append('\n');
        }
        if (StringUtils.hasText(locationInfo.city())) {
            result.append("- 城市：").append(locationInfo.city()).append('\n');
        }
    }

    private void appendRouteSummary(StringBuilder result,
                                    TravelMode mode,
                                    LocationInfo origin,
                                    LocationInfo destination,
                                    JsonNode routeResult) {
        result.append("\n路线信息：\n")
                .append("- 起点：").append(origin.displayName()).append("（").append(origin.location()).append("）\n")
                .append("- 终点：").append(destination.displayName()).append("（").append(destination.location()).append("）\n")
                .append("- 方式：").append(mode.label()).append('\n');

        if (mode == TravelMode.BICYCLING) {
            JsonNode path = firstElement(routeResult.path("data").path("paths"));
            appendCommonPathSummary(result, path);
            return;
        }

        if (mode == TravelMode.TRANSIT) {
            JsonNode route = routeResult.path("route");
            JsonNode transit = firstElement(route.path("transits"));
            if (transit == null) {
                throw new IllegalStateException("高德公交/地铁路线返回为空");
            }
            result.append("- 总距离：").append(formatDistance(text(route.path("distance")))).append('\n')
                    .append("- 预计耗时：").append(formatDuration(text(transit.path("duration")))).append('\n')
                    .append("- 步行距离：").append(formatDistance(text(transit.path("walking_distance")))).append('\n');
            List<String> segments = summarizeTransitSegments(transit.path("segments"));
            if (!segments.isEmpty()) {
                result.append("- 关键步骤：\n");
                for (int index = 0; index < segments.size(); index++) {
                    result.append("  ").append(index + 1).append(". ").append(segments.get(index)).append('\n');
                }
            }
            return;
        }

        JsonNode path = firstElement(routeResult.path("route").path("paths"));
        appendCommonPathSummary(result, path);
    }

    private void appendCommonPathSummary(StringBuilder result, JsonNode path) {
        if (path == null) {
            throw new IllegalStateException("高德路线返回为空");
        }
        result.append("- 总距离：").append(formatDistance(text(path.path("distance")))).append('\n')
                .append("- 预计耗时：").append(formatDuration(text(path.path("duration")))).append('\n');
        List<String> instructions = summarizeStepInstructions(path.path("steps"));
        if (!instructions.isEmpty()) {
            result.append("- 关键步骤：\n");
            for (int index = 0; index < instructions.size(); index++) {
                result.append("  ").append(index + 1).append(". ").append(instructions.get(index)).append('\n');
            }
        }
    }

    private List<String> summarizeTransitSegments(JsonNode segments) {
        List<String> summary = new ArrayList<>();
        if (!segments.isArray()) {
            return summary;
        }
        for (JsonNode segment : segments) {
            JsonNode buslines = segment.path("bus").path("buslines");
            if (buslines.isArray() && !buslines.isEmpty()) {
                JsonNode busline = buslines.get(0);
                String name = text(busline.path("name"));
                String departure = text(busline.path("departure_stop").path("name"));
                String arrival = text(busline.path("arrival_stop").path("name"));
                String departureText = StringUtils.hasText(departure) ? "从" + departure : null;
                String arrivalText = StringUtils.hasText(arrival) ? "到" + arrival : null;
                summary.add(joinNonBlank("乘坐 " + name, departureText, arrivalText));
                continue;
            }

            JsonNode railway = segment.path("railway");
            if (!railway.isMissingNode() && railway.size() > 0) {
                String railwayName = text(railway.path("name"));
                if (StringUtils.hasText(railwayName)) {
                    summary.add("乘坐 " + railwayName);
                    continue;
                }
            }

            JsonNode walking = segment.path("walking");
            String walkingDistance = text(walking.path("distance"));
            if (StringUtils.hasText(walkingDistance) && !"0".equals(walkingDistance)) {
                summary.add("步行 " + formatDistance(walkingDistance));
            }
        }
        return summary.stream()
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();
    }

    private List<String> summarizeStepInstructions(JsonNode steps) {
        List<String> summary = new ArrayList<>();
        if (!steps.isArray()) {
            return summary;
        }
        for (JsonNode step : steps) {
            String instruction = text(step.path("instruction"));
            if (StringUtils.hasText(instruction)) {
                summary.add(instruction);
            }
        }
        return summary.stream().limit(5).toList();
    }

    private JsonNode firstElement(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        return node.get(0);
    }

    private String extractTextPayload(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String extracted = extractTextPayload(item);
                if (StringUtils.hasText(extracted)) {
                    return extracted;
                }
            }
            return null;
        }
        if (node.isObject()) {
            if (node.has("content")) {
                String extracted = extractTextPayload(node.get("content"));
                if (StringUtils.hasText(extracted)) {
                    return extracted;
                }
            }
            if (node.has("text")) {
                return text(node.get("text"));
            }
        }
        return null;
    }

    private boolean isBusinessPayload(JsonNode node) {
        return node != null
                && node.isObject()
                && !node.has("content")
                && !node.has("text");
    }

    private JsonNode tryReadTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String trimDestination(String value) {
        String destination = value;
        for (String separator : DESTINATION_SEPARATORS) {
            int index = destination.indexOf(separator);
            if (index >= 0) {
                destination = destination.substring(0, index);
                break;
            }
        }
        return destination;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String cleanupPlace(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        while (StringUtils.hasText(normalized)
                && (normalized.startsWith("从")
                || normalized.startsWith("到")
                || normalized.startsWith("去")
                || normalized.startsWith("往"))) {
            normalized = normalized.substring(1).trim();
        }
        for (String prefix : List.of("前往", "去往", "由")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length()).trim();
            }
        }
        normalized = normalized.replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .trim();
        return normalized;
    }

    private String cleanupOriginCandidate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
        int punctuationIndex = lastIndexOfAny(candidate, List.of("，", ",", "。", "；", ";"));
        if (punctuationIndex >= 0 && punctuationIndex + 1 < candidate.length()) {
            candidate = candidate.substring(punctuationIndex + 1).trim();
        }

        int bestMarkerEnd = -1;
        for (String marker : REQUEST_PREFIX_MARKERS) {
            int markerIndex = candidate.lastIndexOf(marker);
            if (markerIndex >= 0) {
                bestMarkerEnd = Math.max(bestMarkerEnd, markerIndex + marker.length());
            }
        }
        if (bestMarkerEnd >= 0 && bestMarkerEnd < candidate.length()) {
            candidate = candidate.substring(bestMarkerEnd).trim();
        }

        candidate = cleanupPlace(candidate);
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        return switch (candidate) {
            case "怎么", "如何", "路线", "导航" -> null;
            default -> candidate;
        };
    }

    private int lastIndexOfAny(String text, List<String> fragments) {
        int lastIndex = -1;
        for (String fragment : fragments) {
            lastIndex = Math.max(lastIndex, text.lastIndexOf(fragment));
        }
        return lastIndex;
    }

    private boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String joinNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("，"));
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private String formatDistance(String metersText) {
        if (!StringUtils.hasText(metersText)) {
            return "未知";
        }
        try {
            double meters = Double.parseDouble(metersText);
            if (meters >= 1000) {
                return String.format(Locale.ROOT, "%.1f 公里", meters / 1000.0);
            }
            return String.format(Locale.ROOT, "%.0f 米", meters);
        } catch (NumberFormatException e) {
            return metersText;
        }
    }

    private String formatDuration(String secondsText) {
        if (!StringUtils.hasText(secondsText)) {
            return "未知";
        }
        try {
            long totalSeconds = Long.parseLong(secondsText);
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            if (hours > 0) {
                return minutes > 0 ? hours + "小时" + minutes + "分钟" : hours + "小时";
            }
            return Math.max(minutes, 1) + "分钟";
        } catch (NumberFormatException e) {
            return secondsText;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private enum TravelMode {
        WALKING("步行"),
        BICYCLING("骑行"),
        DRIVING("驾车"),
        TRANSIT("公交/地铁");

        private final String label;

        TravelMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record RouteRequest(String origin,
                                String destination,
                                TravelMode mode,
                                String coordinateTarget,
                                boolean coordinatesRequested) {
        private boolean hasRoute() {
            return StringUtils.hasText(origin) && StringUtils.hasText(destination);
        }
    }

    private record LocationInfo(String query,
                                String displayName,
                                String location,
                                String city,
                                String address) {
    }

    private record RouteEndpoints(String origin, String destination) {
        private static RouteEndpoints empty() {
            return new RouteEndpoints(null, null);
        }

        private boolean complete() {
            return StringUtils.hasText(origin) && StringUtils.hasText(destination);
        }
    }

    private record RouteConnectorMatch(String connector, int index) {
    }
}
