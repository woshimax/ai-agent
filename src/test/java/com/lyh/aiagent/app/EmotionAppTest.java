package com.lyh.aiagent.app;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EmotionAppTest {

    @Resource
    private EmotionApp emotionApp;

    @Test
    void testChat() {
        //通过同一个chatid来保证会话记忆
        String chatId = UUID.randomUUID().toString();

        String message = "你好，我是中国科学院大学李明锴";
        //在doChat里配置了流式调用，这边就流式输出；同理，那边用同步call，这边也用同步输出
        emotionApp.doChat(message, chatId).doOnNext(System.out::print).blockLast();
        System.out.println();

        message = "我想找到官宦千金作为我的另一半,他叫杭州女性";
        emotionApp.doChat(message, chatId).doOnNext(System.out::print).blockLast();
        System.out.println();

        message = "我叫什么来着？刚跟你说过，帮我回忆一下";
        emotionApp.doChat(message, chatId).doOnNext(System.out::print).blockLast();
        System.out.println();
    }



}