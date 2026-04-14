package com.lyh.aiagent.controller;

import com.lyh.aiagent.agent.Manus;
import com.lyh.aiagent.agent.ManusFactory;
import com.lyh.aiagent.common.Result;
import com.lyh.aiagent.tools.PdfTool;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manus 全能助手 Controller
 * 提供 Manus Agent 的对话接口
 */
@Slf4j
@RestController
@RequestMapping("/manus")
@RequiredArgsConstructor
public class ManusController {

    private final ManusFactory manusFactory;
    private final ChatMemory manusChatMemory;

    /**
     * Manus 对话接口（流式）
     *
     * @param message 用户消息
     * @param chatId 对话 ID，同一个 chatId 会共享会话记忆
     * @return SSE 流式响应
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam("message") String message,
                             @RequestParam(value = "chatId", required = false) String chatId) {
        log.info("收到 Manus 请求 - chatId: {}, 用户消息: {}", chatId, message);

        // 创建 Sink 用于推送流式数据
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicReference<Manus> manusRef = new AtomicReference<>();

        // 异步执行 Agent
        Thread worker = new Thread(() -> {
            try {
                // 每次创建新的 Manus 实例，避免状态污染
                Manus manus = manusFactory.create(chatId);
                manusRef.set(manus);

                // 执行 Agent，传入步骤回调
                manus.run(message, (stepInfo) -> {
                    // 推送步骤信息到前端
                    Sinks.EmitResult emitResult = sink.tryEmitNext(stepInfo);
                    if (emitResult.isFailure()) {
                        log.debug("Manus SSE 推送失败，准备取消执行 - chatId: {}, emitResult: {}", chatId, emitResult);
                        manus.requestCancel("SSE subscriber unavailable");
                        Thread.currentThread().interrupt();
                    }
                });

                log.info("Manus 执行完成 - 步数: {}/{}, 状态: {}",
                        manus.getCurrentStep(),
                        manus.getMaxSteps(),
                        manus.getState());

                // 完成流
                if (streamClosed.compareAndSet(false, true)) {
                    sink.tryEmitComplete();
                }

            } catch (Exception e) {
                if (streamClosed.get()) {
                    log.debug("Manus 流已关闭，忽略后续异常 - chatId: {}", chatId, e);
                    return;
                }
                log.error("Manus 执行失败", e);
                if (streamClosed.compareAndSet(false, true)) {
                    sink.tryEmitError(e);
                }
            }
        }, "manus-chat-" + (chatId == null ? "anonymous" : chatId));
        workerRef.set(worker);
        worker.start();

        Runnable cancelRun = () -> {
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }
            Manus manus = manusRef.get();
            if (manus != null) {
                manus.requestCancel("SSE client disconnected");
            }
            Thread runningThread = workerRef.get();
            if (runningThread != null) {
                runningThread.interrupt();
            }
            log.debug("Manus SSE 客户端已断开，停止后台执行 - chatId: {}", chatId);
        };

        return sink.asFlux()
                .doOnCancel(cancelRun)
                .doFinally(signalType -> {
                    if (signalType == reactor.core.publisher.SignalType.CANCEL) {
                        cancelRun.run();
                    }
                });
    }

    /**
     * Manus 对话接口（非流式，保留用于兼容）
     *
     * @param request 包含用户消息的请求对象
     * @return Agent 执行结果
     */
    @PostMapping("/chat")
    public Result<?> chatSync(@RequestBody ManusRequest request) {
        try {
            log.info("收到 Manus 同步请求 - chatId: {}, 用户消息: {}", request.getChatId(), request.getMessage());

            // 每次创建新的 Manus 实例，避免状态污染
            Manus manus = manusFactory.create(request.getChatId());

            // 执行 Agent
            String result = manus.run(request.getMessage());

            // 构建响应
            ManusResponse response = new ManusResponse();
            response.setResult(result);
            response.setAgentName(manus.getName());
            response.setExecutedSteps(manus.getCurrentStep());
            response.setMaxSteps(manus.getMaxSteps());
            response.setFinalState(manus.getState().name());

            log.info("Manus 执行完成 - 步数: {}/{}, 状态: {}",
                    manus.getCurrentStep(),
                    manus.getMaxSteps(),
                    manus.getState());

            return Result.success(response);

        } catch (Exception e) {
            log.error("Manus 执行失败", e);
            return Result.error(500, "Manus 执行失败：" + e.getMessage());
        }
    }

    /**
     * 获取 Manus 信息
     *
     * @return Manus 的基本信息
     */
    @GetMapping("/info")
    public Result<ManusInfo> getInfo() {
        ManusInfo info = new ManusInfo();
        info.setName("Manus");
        info.setDescription("全能型人工智能助手，致力于解决用户提出的任何任务");
        info.setType("ReAct Agent");
        info.setMaxSteps(20);
        info.setFeatures(new String[]{
            "多工具协同调用",
            "ReAct 思考-行动模式",
            "自动任务分解",
            "步骤化执行控制"
        });
        return Result.success(info);
    }

    @GetMapping("/history")
    public Result<List<Map<String, String>>> history(@RequestParam("chatId") String chatId) {
        List<Message> messages = manusChatMemory.get(chatId, 100);
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : messages) {
            String role;
            if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "ai";
            } else {
                continue;
            }
            Map<String, String> item = new HashMap<>();
            item.put("role", role);
            item.put("content", msg.getText());
            result.add(item);
        }
        return Result.success(result);
    }

    @PostMapping("/clear")
    public Result<Boolean> clearHistory(@RequestParam("chatId") String chatId) {
        manusChatMemory.clear(chatId);
        return Result.success(true);
    }

    @GetMapping("/files/pdfs/{filename:.+}")
    public ResponseEntity<Resource> downloadPdf(@PathVariable("filename") String filename) throws MalformedURLException {
        Path filePath = PdfTool.BASE_DIR.resolve(filename).normalize();
        if (!filePath.startsWith(PdfTool.BASE_DIR) || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(filePath.toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filePath.getFileName().toString(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(resource);
    }

    /**
     * 请求对象
     */
    @Data
    public static class ManusRequest {
        /**
         * 用户消息
         */
        private String message;

        /**
         * 对话 ID；传相同值可启用多轮记忆
         */
        private String chatId;
    }

    /**
     * 响应对象
     */
    @Data
    public static class ManusResponse {
        /**
         * 执行结果
         */
        private String result;

        /**
         * Agent 名称
         */
        private String agentName;

        /**
         * 实际执行的步数
         */
        private int executedSteps;

        /**
         * 最大步数限制
         */
        private int maxSteps;

        /**
         * 最终状态
         */
        private String finalState;
    }

    /**
     * Manus 信息对象
     */
    @Data
    public static class ManusInfo {
        /**
         * 名称
         */
        private String name;

        /**
         * 描述
         */
        private String description;

        /**
         * 类型
         */
        private String type;

        /**
         * 最大步数
         */
        private int maxSteps;

        /**
         * 特性列表
         */
        private String[] features;
    }
}
