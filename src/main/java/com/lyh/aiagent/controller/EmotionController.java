package com.lyh.aiagent.controller;

import com.lyh.aiagent.app.EmotionApp;
import com.lyh.aiagent.app.EmotionReport;
import com.lyh.aiagent.common.Result;
import com.lyh.aiagent.service.ChatService;
import com.lyh.aiagent.service.ReportService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/emotion")
public class EmotionController {

    @Resource
    private EmotionApp emotionApp;

    @Resource
    private ChatService chatService;

    @Resource
    private ReportService reportService;

    /**
     * 获取历史消息
     * GET /api/emotion/history?chatId=xxx
     */
    @GetMapping("/history")
    public Result<List<Map<String, String>>> history(@RequestParam("chatId") String chatId) {
        return Result.success(emotionApp.getChatHistory(chatId, 100));
    }

    /**
     * 流式对话
     * GET /api/emotion/chat?message=xxx&chatId=xxx
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam("message") String message, @RequestParam("chatId") String chatId) {
        return emotionApp.doChat(message, chatId);
    }

    /**
     * 生成对话标题
     * POST /api/emotion/title?message=xxx&chatId=xxx
     */
    @PostMapping("/title")
    public Result<String> generateTitle(@RequestParam("message") String message, @RequestParam("chatId") String chatId) {
        String title = emotionApp.generateTitle(message);
        if (title != null) {
            chatService.updateChatName(chatId, title);
        }
        return Result.success(title);
    }

    /**
     * 基于对话历史生成心理分析报告
     * GET /api/emotion/report?chatId=xxx
     */
    @GetMapping("/report")
    public Result<EmotionReport> report(@RequestParam("chatId") String chatId) {
        return Result.success(reportService.getOrGenerate(chatId));
    }
}
