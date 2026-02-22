package com.lyh.aiagent.controller;

import com.lyh.aiagent.common.Result;
import com.lyh.aiagent.model.Chat;
import com.lyh.aiagent.model.User;
import com.lyh.aiagent.service.ChatService;
import com.lyh.aiagent.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ChatService chatService;

    @PostMapping("/register")
    public Result<User> register(@RequestParam("username") String username,
                                 @RequestParam("password") String password) {
        return Result.success(userService.register(username, password));
    }

    @PostMapping("/login")
    public Result<User> login(@RequestParam("username") String username,
                              @RequestParam("password") String password) {
        return Result.success(userService.login(username, password));
    }

    @GetMapping("/get")
    public Result<User> get(@RequestParam("id") Long id) {
        return Result.success(userService.getById(id));
    }

    @PostMapping("/chat/create")
    public Result<Chat> createChat(@RequestParam("userId") Long userId) {
        return Result.success(chatService.createChat(userId));
    }

    @GetMapping("/chat/list")
    public Result<List<Chat>> listChats(@RequestParam("userId") Long userId) {
        return Result.success(chatService.listByUserId(userId));
    }
}
