package com.lyh.aiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.lyh.aiagent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();
    private ChatMemory chatMemory;
    private String conversationId;
    private int memoryRetrieveSize = 20;
    private String currentUserPrompt;

    // 保存执行步骤详情（用于调试）
    private List<String> executionSteps = new ArrayList<>();

    // 步骤回调函数（用于流式推送执行状态）
    private Consumer<String> stepCallback;

    // 当前运行开始前已加载的历史消息数量
    private int runMessageStartIndex = 0;

    // 取消执行标记
    private volatile boolean cancelRequested;
    private String cancelReason;

    /**
     * 运行代理（支持步骤回调）
     *
     * @param userPrompt 用户提示词
     * @param stepCallback 步骤回调函数，每执行一步后调用
     * @return 执行结果（最终的 AI 回复）
     */
    public String run(String userPrompt, Consumer<String> stepCallback) {
        this.stepCallback = stepCallback;
        return run(userPrompt);
    }

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果（最终的 AI 回复）
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state == AgentState.RUNNING) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        prepareForRun();
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        restoreConversationHistory();
        this.runMessageStartIndex = messageList.size();
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        this.currentUserPrompt = userPrompt;
        // 记录消息上下文
        UserMessage currentUserMessage = new UserMessage(userPrompt);
        messageList.add(currentUserMessage);
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                if (isCancellationRequested()) {
                    state = AgentState.CANCELLED;
                    break;
                }
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);

                // 推送步骤开始状态
                if (stepCallback != null) {
                    stepCallback.accept("STEP_START:" + stepNumber);
                }
                if (isCancellationRequested()) {
                    state = AgentState.CANCELLED;
                    break;
                }

                // 单步执行
                String stepResult = step();
                if (isCancellationRequested()) {
                    state = AgentState.CANCELLED;
                    break;
                }
                String stepInfo = "Step " + stepNumber + ": " + stepResult;
                executionSteps.add(stepInfo);

                // 推送步骤完成状态
                if (stepCallback != null) {
                    stepCallback.accept("STEP_DONE:" + stepNumber + ":" + stepResult);
                }
            }
            if (state == AgentState.CANCELLED || isCancellationRequested()) {
                state = AgentState.CANCELLED;
                executionSteps.add("Terminated: Cancelled" + (StrUtil.isNotBlank(cancelReason) ? " (" + cancelReason + ")" : ""));
                log.info("{} execution cancelled, reason={}", name, StrUtil.blankToDefault(cancelReason, "unknown"));
                return "执行已取消";
            }
            // 检查是否超出步骤限制
            if (state != AgentState.FINISHED && currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                executionSteps.add("Terminated: Reached max steps (" + maxSteps + ")");
                if (stepCallback != null) {
                    stepCallback.accept("MAX_STEPS_REACHED");
                }
            }

            // 从消息历史中提取最终的 AI 回复
            String finalResponse = extractFinalResponse();
            persistConversationTurn(currentUserMessage, finalResponse);

            // 推送最终结果（使用特殊标记避免换行符问题）
            if (stepCallback != null) {
                // 替换换行符为特殊标记，避免破坏 SSE 格式
                String encodedResponse = finalResponse.replace("\n", "\\n").replace("\r", "");
                stepCallback.accept("FINAL_RESPONSE:" + encodedResponse);
            }

            return finalResponse;
        } catch (Exception e) {
            if (isCancellationRequested()) {
                state = AgentState.CANCELLED;
                log.info("{} execution cancelled while handling exception, reason={}", name, StrUtil.blankToDefault(cancelReason, "unknown"));
                return "执行已取消";
            }
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            String errorMessage = "执行错误：" + e.getMessage();
            if (stepCallback != null) {
                String encodedResponse = errorMessage.replace("\n", "\\n").replace("\r", "");
                stepCallback.accept("FINAL_RESPONSE:" + encodedResponse);
            }
            return errorMessage;
        } finally {
            // 3、清理资源
            this.cleanup();
        }
    }

    /**
     * 从消息历史中提取最终的 AI 回复
     *
     * @return 最终的 AI 回复内容
     */
    private String extractFinalResponse() {
        // 从本轮消息范围内从后往前找最后一条 AssistantMessage
        for (int i = messageList.size() - 1; i >= runMessageStartIndex; i--) {
            Message message = messageList.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                String content = assistantMessage.getText();
                if (StrUtil.isNotBlank(content)) {
                    return normalizeFinalResponse(content);
                }
            }
        }
        // 如果没有找到有效的回复，返回执行摘要
        return "任务执行完成，共执行了 " + currentStep + " 个步骤。";
    }

    /**
     * 获取执行步骤详情（用于调试）
     *
     * @return 执行步骤列表
     */
    public List<String> getExecutionSteps() {
        return new ArrayList<>(executionSteps);
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 为新一轮运行重置状态
     */
    protected void prepareForRun() {
        if (this.state != AgentState.IDLE || currentStep != 0 || !messageList.isEmpty()) {
            log.info("Resetting agent runtime state before new run. previousState={}", this.state);
        }
        this.state = AgentState.IDLE;
        this.currentStep = 0;
        this.runMessageStartIndex = 0;
        this.currentUserPrompt = null;
        this.cancelRequested = false;
        this.cancelReason = null;
        this.messageList.clear();
        this.executionSteps.clear();
        resetRunContext();
    }

    /**
     * 子类可以重写此方法来清理本轮运行的额外上下文
     */
    protected void resetRunContext() {
        // 子类按需重写
    }

    /**
     * 子类可以重写此方法，对最终输出做规范化处理
     */
    protected String normalizeFinalResponse(String responseText) {
        return responseText;
    }

    private void restoreConversationHistory() {
        if (chatMemory == null || StrUtil.isBlank(conversationId) || memoryRetrieveSize <= 0) {
            return;
        }
        List<Message> historyMessages = chatMemory.get(conversationId, memoryRetrieveSize);
        if (historyMessages == null || historyMessages.isEmpty()) {
            return;
        }
        messageList.addAll(historyMessages);
        log.info("Loaded {} history messages for conversationId={}", historyMessages.size(), conversationId);
    }

    private void persistConversationTurn(UserMessage currentUserMessage, String finalResponse) {
        if (chatMemory == null || StrUtil.isBlank(conversationId) || StrUtil.isBlank(finalResponse)) {
            return;
        }
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(currentUserMessage);
        turnMessages.add(new AssistantMessage(finalResponse));
        chatMemory.add(conversationId, turnMessages);
        log.info("Persisted {} conversation messages for conversationId={}", turnMessages.size(), conversationId);
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        this.stepCallback = null;
    }

    public void requestCancel(String reason) {
        if (!this.cancelRequested) {
            log.info("{} received cancel request, reason={}", StrUtil.blankToDefault(name, "Agent"), StrUtil.blankToDefault(reason, "unknown"));
        }
        this.cancelRequested = true;
        this.cancelReason = reason;
    }

    public boolean isCancellationRequested() {
        return this.cancelRequested || Thread.currentThread().isInterrupted();
    }

    /**
     * 通知步骤回调（供子类调用）
     *
     * @param message 要推送的消息
     */
    protected void notifyStepCallback(String message) {
        if (!isCancellationRequested() && stepCallback != null) {
            stepCallback.accept(message);
        }
    }
}
