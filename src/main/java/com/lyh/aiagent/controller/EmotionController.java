package com.lyh.aiagent.controller;

import com.lyh.aiagent.app.EmotionApp;
import com.lyh.aiagent.app.EmotionReport;
import com.lyh.aiagent.common.Result;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/emotion")
public class EmotionController {

    @Resource
    private EmotionApp emotionApp;

    /**
     * 流式对话
     * GET /api/emotion/chat?message=xxx&chatId=xxx
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam("message") String message, @RequestParam("chatId") String chatId) {
        return emotionApp.doChat(message, chatId);
    }

    /**
     * 生成情感分析报告
     * GET /api/emotion/report?message=xxx&chatId=xxx
     */
    @GetMapping("/report")
    public Result<EmotionReport> report(@RequestParam("message") String message, @RequestParam("chatId") String chatId) {
        return Result.success(emotionApp.doChatWithReport(message, chatId));
    }
}
