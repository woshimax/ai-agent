package com.lyh.aiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.lyh.aiagent.common.ToolCallbackResolver;
import com.lyh.aiagent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    private static final Pattern TERMINATE_TOOL_CALL_PATTERN = Pattern.compile(
            "doTerminate\\s*\\(\\s*finalMessage\\s*:\\s*\"([\\s\\S]*?)\"\\s*\\)\\s*$"
    );
    private static final Pattern TOOL_CODE_BLOCK_PATTERN = Pattern.compile("(?s)```tool_code\\s*.*?```");
    private static final Pattern STANDALONE_FUNCTION_CALL_PATTERN = Pattern.compile("(?m)^\\s*[a-zA-Z][\\w-]*\\([^\\n]*\\)\\s*$");
    private static final Pattern ROUTE_INTENT_PATTERN = Pattern.compile(
            "(怎么走|怎么去|如何去|路线|导航|前往|到达|从.+到.+|公交|地铁|打车|驾车|自驾|步行|骑行|route|directions?|navigate|from .+ to .+)",
            Pattern.CASE_INSENSITIVE
    );

    // 可用的工具
    private final Object[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final DashScopeChatOptions chatOptions;

    // 保存最后一次 AI 回复的文本内容（用于提取最终回复）
    private String lastAssistantText;

    // 保存所有 AI 生成的文本内容（按步骤累积）
    private final List<String> allAssistantTexts = new ArrayList<>();
    private boolean pdfReminderIssued;
    private boolean pdfGeneratedThisRun;
    private boolean invalidToolSyntaxReminderIssued;
    private int routeToolReminderCount;
    private int routeToolFailureCount;
    private boolean routeToolCompletedThisRun;

    public ToolCallAgent(Object[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withProxyToolCalls(true)
                .build();
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        refreshExecutableCallbacks();
        // 1、调用 AI 大模型，获取工具调用结果
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        String systemPrompt = buildSystemPrompt();
        try {
            FunctionCallback[] executableCallbacks = ToolCallbackResolver.resolveToArray(availableTools);
            log.info("{} 本轮可调用工具: {}", getName(),
                    Arrays.stream(executableCallbacks)
                            .map(FunctionCallback::getName)
                            .collect(Collectors.joining(", ")));
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(systemPrompt)
                    .tools(executableCallbacks)
                    .call()
                    .chatResponse();
            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 3、解析工具调用结果，获取要调用的工具
            // 助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 获取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 输出提示信息
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);

            // 保存最后一次的文本内容（用于后续提取最终回复）
            if (StrUtil.isNotBlank(result)) {
                this.lastAssistantText = result;
                // 同时累积所有文本内容
                this.allAssistantTexts.add(result);
                // 推送思考内容到前端（替换换行符避免破坏 SSE 格式）
                String displayResult = normalizeFinalResponse(result);
                String encodedResult = displayResult.replace("\n", "\\n").replace("\r", "");
                notifyStepCallback("THINKING:" + encodedResult);
            }

            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (handleRouteToolEnforcement(toolCallList, assistantMessage)) {
                return false;
            }
            // 如果不需要调用工具，返回 false
            if (toolCallList.isEmpty()) {
                if (shouldForcePdfGeneration()) {
                    getMessageList().add(assistantMessage);
                    getMessageList().add(new UserMessage("""
                            系统提醒：用户明确要求生成 PDF 文件。
                            不要只提供文字说明、TXT 文件，或让用户自行另存为 PDF。
                            请立即调用 generatePdf 工具生成 PDF 文件，然后再使用 doTerminate 返回最终答案。
                            """));
                    this.pdfReminderIssued = true;
                    log.info("{} 检测到用户明确要求 PDF 但尚未生成，追加纠偏提示并继续下一步", getName());
                    return false;
                }
                if (looksLikePseudoToolUsage(result) && !invalidToolSyntaxReminderIssued) {
                    getMessageList().add(assistantMessage);
                    getMessageList().add(new UserMessage("""
                            系统提醒：不要把思考过程、计划说明、```tool_code``` 代码块或伪函数调用直接展示给用户。
                            如果需要使用工具，必须发起真实工具调用；如果不需要工具，请直接调用 doTerminate，只返回最终答案。
                            不要输出类似 getMapDirections(...)、searchWeb(...) 这样的文本示例。
                            """));
                    this.invalidToolSyntaxReminderIssued = true;
                    log.info("{} 检测到伪工具调用文本，追加纠偏提示并继续下一步", getName());
                    return false;
                }
                // 只有不调用工具时，才需要手动记录助手消息，并结束当前任务
                getMessageList().add(assistantMessage);
                setState(AgentState.FINISHED);
                log.info("{} 未调用工具，直接输出最终答案，任务结束", getName());
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
        }
    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        refreshExecutableCallbacks();
        // 推送工具调用信息
        AssistantMessage currentAssistantMsg = toolCallChatResponse.getResult().getOutput();
        boolean mapToolSelected = currentAssistantMsg != null
                && currentAssistantMsg.getToolCalls() != null
                && currentAssistantMsg.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name)
                .anyMatch(this::isMapToolName);
        if (currentAssistantMsg != null && !currentAssistantMsg.getToolCalls().isEmpty()) {
            String toolNames = currentAssistantMsg.getToolCalls().stream()
                    .map(AssistantMessage.ToolCall::name)
                    .collect(Collectors.joining(", "));
            notifyStepCallback("TOOL_CALL:正在调用工具: " + toolNames);
        }

        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        } catch (Exception e) {
            if (mapToolSelected) {
                this.routeToolFailureCount++;
            }
            throw e;
        }
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        this.pdfGeneratedThisRun = this.pdfGeneratedThisRun || toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("generatePdf"));
        this.routeToolCompletedThisRun = this.routeToolCompletedThisRun || toolResponseMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::name)
                .anyMatch(this::isRoutePlanningToolName);

        if (terminateToolCalled) {
            String finalResponseText = null;

            // 1. 优先从 doTerminate 工具的 finalMessage 参数中提取
            AssistantMessage lastAssistantMsg = (AssistantMessage) getMessageList().stream()
                    .filter(msg -> msg instanceof AssistantMessage)
                    .reduce((first, second) -> second)
                    .orElse(null);

            if (lastAssistantMsg != null && !lastAssistantMsg.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall tc : lastAssistantMsg.getToolCalls()) {
                    if (tc.name().equals("doTerminate")) {
                        String args = tc.arguments();
                        String finalMessage = extractFinalMessage(args);
                        if (StrUtil.isNotBlank(finalMessage)) {
                            finalResponseText = finalMessage;
                            log.info("从 doTerminate 工具参数提取到最终回复（长度: {}）", finalMessage.length());
                            break;
                        }
                    }
                }
            }

            // 2. 如果工具参数中没有，尝试从工具返回结果中提取
            if (StrUtil.isBlank(finalResponseText)) {
                finalResponseText = toolResponseMessage.getResponses().stream()
                        .filter(response -> response.name().equals("doTerminate"))
                        .map(response -> extractFinalMessageFromToolResponse(response.responseData()))
                        .filter(StrUtil::isNotBlank)
                        .findFirst()
                        .orElse(null);
                if (StrUtil.isNotBlank(finalResponseText)) {
                    log.info("从 doTerminate 工具返回结果提取到最终回复（长度: {}）", finalResponseText.length());
                }
            }

            // 3. 如果工具参数和返回结果中都没有，使用 lastAssistantText
            if (StrUtil.isBlank(finalResponseText) && StrUtil.isNotBlank(this.lastAssistantText)) {
                // 检测是否为过渡性语句
                boolean isTransitionalPhrase = this.lastAssistantText.contains("让我") ||
                        this.lastAssistantText.contains("我将") ||
                        this.lastAssistantText.contains("我会") ||
                        this.lastAssistantText.contains("准备") ||
                        (this.lastAssistantText.length() < 100 && this.allAssistantTexts.size() > 1);

                if (isTransitionalPhrase && this.allAssistantTexts.size() > 1) {
                    // 如果是过渡性语句且有历史内容，尝试找到最完整的内容
                    String longestText = this.allAssistantTexts.stream()
                            .max((a, b) -> Integer.compare(a.length(), b.length()))
                            .orElse(this.lastAssistantText);
                    finalResponseText = longestText;
                    log.info("检测到过渡性语句，使用最长的 AssistantText 作为最终回复（长度: {}）", finalResponseText.length());
                } else {
                    finalResponseText = this.lastAssistantText;
                    log.info("使用 lastAssistantText 作为最终回复（长度: {}）", finalResponseText.length());
                }
            }

            // 4. 如果都没有，尝试拼接所有内容（作为最后的降级方案）
            if (StrUtil.isBlank(finalResponseText) && !this.allAssistantTexts.isEmpty()) {
                finalResponseText = String.join("\n\n", this.allAssistantTexts);
                log.info("拼接所有 AssistantText 作为最终回复（长度: {}）", finalResponseText.length());
            }

            // 添加最终回复到消息列表
            if (StrUtil.isNotBlank(finalResponseText)) {
                getMessageList().add(new AssistantMessage(finalResponseText));
            }

            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }

        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);

        // 推送工具执行结果（非 terminate 工具）
        if (!terminateToolCalled) {
            String resultSummary = toolResponseMessage.getResponses().stream()
                    .map(response -> response.name() + ": " +
                            (response.responseData().length() > 100
                                ? response.responseData().substring(0, 100) + "..."
                                : response.responseData()))
                    .collect(Collectors.joining("; "));
            notifyStepCallback("TOOL_RESULT:" + resultSummary);
        }

        toolResponseMessage.getResponses().stream()
                .filter(response -> response.name().equals("generatePdf"))
                .map(response -> buildPdfReadyEvent(response.responseData()))
                .filter(StrUtil::isNotBlank)
                .forEach(event -> notifyStepCallback("FILE_READY:" + event));

        return results;
    }

    /**
     * 从工具调用参数中提取 finalMessage
     *
     * @param jsonArgs JSON 格式的参数
     * @return 提取的最终消息
     */
    protected String extractFinalMessage(String jsonArgs) {
        try {
            if (StrUtil.isBlank(jsonArgs)) {
                return null;
            }
            // 使用 hutool 的 JSONUtil 解析 JSON
            cn.hutool.json.JSONObject jsonObject = cn.hutool.json.JSONUtil.parseObj(jsonArgs);
            String finalMessage = jsonObject.getStr("finalMessage");
            if (StrUtil.isNotBlank(finalMessage)) {
                return finalMessage;
            }
            String arg0 = jsonObject.getStr("arg0");
            if (StrUtil.isNotBlank(arg0)) {
                return arg0;
            }
            if (!jsonObject.isEmpty()) {
                Object firstValue = jsonObject.values().stream().findFirst().orElse(null);
                if (firstValue != null) {
                    return firstValue.toString();
                }
            }
        } catch (Exception e) {
            log.error("提取 finalMessage 失败，尝试使用正则表达式", e);
            try {
                // 降级方案：使用正则表达式
                String pattern = "\"finalMessage\"\\s*:\\s*\"([^\"]+)\"";
                java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = r.matcher(jsonArgs);
                if (m.find()) {
                    return m.group(1);
                }
                String arg0Pattern = "\"arg0\"\\s*:\\s*\"([\\s\\S]*?)\"";
                java.util.regex.Pattern arg0Regex = java.util.regex.Pattern.compile(arg0Pattern);
                java.util.regex.Matcher arg0Matcher = arg0Regex.matcher(jsonArgs);
                if (arg0Matcher.find()) {
                    return arg0Matcher.group(1);
                }
            } catch (Exception ex) {
                log.error("正则表达式提取也失败", ex);
            }
        }
        return null;
    }

    protected String extractFinalMessageFromToolResponse(String responseData) {
        if (StrUtil.isBlank(responseData)) {
            return null;
        }
        String prefix = "任务已终止，最终回复：";
        String normalized = responseData;
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("\\n", "\n").replace("\\\"", "\"");
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length()).trim();
        }
        return normalized.trim();
    }

    private String buildPdfReadyEvent(String responseData) {
        if (StrUtil.isBlank(responseData)) {
            return null;
        }
        Pattern pattern = Pattern.compile("PDF文件生成成功:\\s*([^，]+)，路径:\\s*(.+)");
        Matcher matcher = pattern.matcher(responseData);
        if (!matcher.find()) {
            return null;
        }
        String filename = matcher.group(1).trim();
        String downloadUrl = "/api/manus/files/pdfs/" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8);
        cn.hutool.json.JSONObject jsonObject = new cn.hutool.json.JSONObject();
        jsonObject.set("type", "pdf");
        jsonObject.set("filename", filename);
        jsonObject.set("downloadUrl", downloadUrl);
        return jsonObject.toString();
    }

    @Override
    protected void resetRunContext() {
        this.toolCallChatResponse = null;
        this.lastAssistantText = null;
        this.allAssistantTexts.clear();
        this.pdfReminderIssued = false;
        this.pdfGeneratedThisRun = false;
        this.invalidToolSyntaxReminderIssued = false;
        this.routeToolReminderCount = 0;
        this.routeToolFailureCount = 0;
        this.routeToolCompletedThisRun = false;
    }

    private boolean shouldForcePdfGeneration() {
        if (pdfReminderIssued || pdfGeneratedThisRun) {
            return false;
        }
        String currentUserPrompt = getCurrentUserPrompt();
        if (StrUtil.isBlank(currentUserPrompt)) {
            return false;
        }
        String lowerPrompt = currentUserPrompt.toLowerCase();
        boolean mentionsPdf = lowerPrompt.contains("pdf");
        boolean asksToGeneratePdf = currentUserPrompt.contains("生成") ||
                currentUserPrompt.contains("导出") ||
                currentUserPrompt.contains("下载") ||
                currentUserPrompt.contains("转成") ||
                currentUserPrompt.contains("转为") ||
                currentUserPrompt.contains("做成") ||
                currentUserPrompt.contains("保存为") ||
                currentUserPrompt.contains("输出");
        return mentionsPdf && asksToGeneratePdf;
    }

    @Override
    protected String normalizeFinalResponse(String responseText) {
        if (StrUtil.isBlank(responseText)) {
            return responseText;
        }
        String trimmed = stripPseudoToolPayload(responseText).trim();
        int toolCallIndex = trimmed.indexOf("doTerminate(");
        if (toolCallIndex < 0) {
            return trimmed;
        }

        String textBeforeToolCall = trimmed.substring(0, toolCallIndex).trim();
        if (StrUtil.isNotBlank(textBeforeToolCall)) {
            return textBeforeToolCall;
        }

        Matcher matcher = TERMINATE_TOOL_CALL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .trim();
        }

        return trimmed.replaceAll("(?s)\\s*doTerminate\\s*\\(.*\\)\\s*$", "").trim();
    }

    private boolean looksLikePseudoToolUsage(String responseText) {
        if (StrUtil.isBlank(responseText)) {
            return false;
        }
        String normalized = responseText.trim();
        return normalized.contains("```tool_code")
                || normalized.contains("工具代码")
                || STANDALONE_FUNCTION_CALL_PATTERN.matcher(normalized).find();
    }

    private String stripPseudoToolPayload(String responseText) {
        if (StrUtil.isBlank(responseText)) {
            return responseText;
        }
        String stripped = TOOL_CODE_BLOCK_PATTERN.matcher(responseText).replaceAll("");
        stripped = STANDALONE_FUNCTION_CALL_PATTERN.matcher(stripped).replaceAll("");
        return stripped.trim();
    }

    private String buildSystemPrompt() {
        String systemPrompt = getSystemPrompt();
        String nextStepPrompt = getNextStepPrompt();
        String strategyPrompt = buildStrategyPrompt();
        String combinedPrompt = appendPrompt(systemPrompt, nextStepPrompt);
        combinedPrompt = appendPrompt(combinedPrompt, strategyPrompt);
        return combinedPrompt;
    }

    private String buildStrategyPrompt() {
        if (isRouteIntent() && hasMapToolAvailable()) {
            if (hasAmapRouteFacadeAvailable()) {
                return """
                        当前用户问题命中“路线/导航”意图。
                        工具选择规则：
                        1. 优先调用 `planRouteWithAmap`，并把用户原话完整传入，由它自动调用高德地图完成地点解析、经纬度查询和路线规划。
                        2. 只有当 `planRouteWithAmap` 不可用时，才退回到底层 `maps_text_search` / `maps_geo` / `maps_direction_walking` / `maps_direction_driving` / `maps_direction_transit_integrated` / `maps_bicycling`
                        3. search / 网页读取只能补充开放时间、攻略、交通说明，不能替代路线规划
                        4. 不要凭常识直接编造路线、时间、票价或站点信息
                        """;
            }
            return """
                    当前用户问题命中“路线/导航”意图。
                    工具选择规则：
                    1. 优先调用地图/路线相关工具获取真实路线，不要先用 search。
                    2. 如果用户给的是地点名/POI（如“抖音集团”“八达岭长城”）而不是经纬度：
                       - 先用 `maps_text_search` 或 `maps_geo` 获取起点坐标
                       - 再用 `maps_text_search` 或 `maps_geo` 获取终点坐标
                       - 然后调用 `maps_direction_walking` / `maps_direction_driving` / `maps_direction_transit_integrated` / `maps_bicycling`
                    3. search / 网页读取只能补充开放时间、攻略、交通说明，不能替代路线规划
                    4. 不要凭常识直接编造路线、时间、票价或站点信息
                    """;
        }
        return null;
    }

    private String appendPrompt(String base, String extra) {
        if (StrUtil.isBlank(extra)) {
            return base;
        }
        if (StrUtil.isBlank(base)) {
            return extra;
        }
        return base + "\n\n" + extra;
    }

    private List<FunctionCallback> resolveExecutableCallbacks(Object[] availableTools) {
        return ToolCallbackResolver.resolve((Object[]) availableTools);
    }

    private boolean shouldForceRouteTool(List<AssistantMessage.ToolCall> toolCallList) {
        if (!isRouteIntent() || !hasMapToolAvailable() || routeToolCompletedThisRun) {
            return false;
        }
        if (toolCallList == null || toolCallList.isEmpty()) {
            return true;
        }
        return toolCallList.stream()
                .map(AssistantMessage.ToolCall::name)
                .noneMatch(this::isMapToolName);
    }

    private boolean isRouteIntent() {
        String currentUserPrompt = getCurrentUserPrompt();
        return StrUtil.isNotBlank(currentUserPrompt) && ROUTE_INTENT_PATTERN.matcher(currentUserPrompt).find();
    }

    private boolean hasMapToolAvailable() {
        return resolveExecutableCallbacks(availableTools).stream()
                .map(FunctionCallback::getName)
                .anyMatch(this::isMapToolName);
    }

    private boolean hasAmapRouteFacadeAvailable() {
        return resolveExecutableCallbacks(availableTools).stream()
                .map(FunctionCallback::getName)
                .anyMatch("planRouteWithAmap"::equals);
    }

    private boolean isMapToolName(String toolName) {
        if (StrUtil.isBlank(toolName)) {
            return false;
        }
        String lowerName = toolName.toLowerCase();
        return lowerName.contains("amap")
                || lowerName.contains("map")
                || lowerName.contains("route")
                || lowerName.contains("direction")
                || lowerName.contains("navigate")
                || lowerName.contains("geo");
    }

    private boolean isRoutePlanningToolName(String toolName) {
        if (StrUtil.isBlank(toolName)) {
            return false;
        }
        String lowerName = toolName.toLowerCase();
        return lowerName.contains("direction")
                || lowerName.contains("route")
                || lowerName.contains("bicycling")
                || lowerName.contains("navigate");
    }

    private void refreshExecutableCallbacks() {
        List<FunctionCallback> callbacks = resolveExecutableCallbacks(this.availableTools);
        this.chatOptions.setFunctionCallbacks(callbacks);
    }

    private boolean handleRouteToolEnforcement(List<AssistantMessage.ToolCall> toolCallList, AssistantMessage assistantMessage) {
        if (!shouldForceRouteTool(toolCallList)) {
            return false;
        }

        this.routeToolReminderCount++;
        if (this.routeToolFailureCount > 0 && this.routeToolReminderCount >= 2) {
            String fallbackMessage = "抱歉，当前地图路线服务暂时不可用，我无法在不调用地图工具的情况下给出可靠导航结果。请稍后重试，或提供更具体的起点位置后我再为您查询。";
            getMessageList().add(new AssistantMessage(fallbackMessage));
            setState(AgentState.FINISHED);
            log.info("{} 地图工具已失败 {} 次，阻止继续臆造路线并直接返回失败说明", getName(), routeToolFailureCount);
            return true;
        }

        getMessageList().add(assistantMessage);
        if (this.routeToolFailureCount > 0) {
            getMessageList().add(new UserMessage("""
                    系统提醒：路线类问题的地图工具刚刚执行失败。
                    请优先再次尝试地图/路线工具；如果再次失败，只能明确告知用户地图服务暂不可用，不要使用搜索结果或常识臆造路线。
                    """));
        } else {
            String reminder = hasAmapRouteFacadeAvailable()
                    ? """
                    系统提醒：当前问题属于“地点到地点的路线/导航”类任务。
                    必须优先调用 `planRouteWithAmap`，并把用户原话完整传入，由它自动使用高德地图完成地点解析、坐标查询和路线规划。
                    不要先用 search、网页搜索或常识直接拼凑路线答案。
                    只有地图工具调用失败或无法覆盖时，才可以使用搜索工具做补充说明。
                    在调用地图工具后，再使用 doTerminate 输出最终路线建议。
                    """
                    : """
                    系统提醒：当前问题属于“地点到地点的路线/导航”类任务。
                    必须优先调用地图/路线相关工具（例如 AMap / maps / route / direction）。
                    如果起点或终点是地点名/POI，而不是经纬度，必须先调用 `maps_text_search` 或 `maps_geo` 获取坐标，再调用 `maps_direction_walking` / `maps_direction_driving` / `maps_direction_transit_integrated` / `maps_bicycling`。
                    不要先用 search、网页搜索或常识直接拼凑路线答案。
                    只有地图工具调用失败或无法覆盖时，才可以使用搜索工具做补充说明。
                    在调用地图工具后，再使用 doTerminate 输出最终路线建议。
                    """;
            getMessageList().add(new UserMessage(reminder));
        }
        log.info("{} 检测到路线类问题未得到有效地图结果，追加纠偏提示并继续下一步（提醒次数: {}, 地图失败次数: {}）",
                getName(), routeToolReminderCount, routeToolFailureCount);
        return true;
    }
}
