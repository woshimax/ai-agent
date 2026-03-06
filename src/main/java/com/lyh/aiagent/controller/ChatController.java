package com.lyh.aiagent.controller;

import com.lyh.aiagent.app.EmotionApp;
import com.lyh.aiagent.common.Result;
import com.lyh.aiagent.model.Chat;
import com.lyh.aiagent.service.ChatService;
import com.lyh.aiagent.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ReportService reportService;
    private final EmotionApp emotionApp;

    @PostMapping("/create")
    public Result<Chat> createChat(@RequestParam("userId") Long userId) {
        return Result.success(chatService.createChat(userId));
    }

    @GetMapping("/list")
    public Result<List<Chat>> listChats(@RequestParam("userId") Long userId) {
        return Result.success(chatService.listByUserId(userId));
    }

    @PostMapping("/rename")
    public Result<Void> renameChat(@RequestParam("chatId") String chatId,
                                   @RequestParam("chatName") String chatName) {
        chatService.updateChatName(chatId, chatName);
        return Result.success(null);
    }

    @PostMapping("/pin")
    public Result<Void> pinChat(@RequestParam("chatId") String chatId,
                                @RequestParam("pinned") Boolean pinned) {
        chatService.pinChat(chatId, pinned);
        return Result.success(null);
    }

    @PostMapping("/delete")
    public Result<Void> deleteChat(@RequestParam("chatId") String chatId) {
        chatService.deleteByChatId(chatId);
        reportService.deleteByChatId(chatId);
        emotionApp.clearChatMemory(chatId);
        return Result.success(null);
    }
}
