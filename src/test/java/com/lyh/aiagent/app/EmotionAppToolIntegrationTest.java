package com.lyh.aiagent.app;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.stream.Collectors;

@SpringBootTest
class EmotionAppToolIntegrationTest {

    @Resource
    private EmotionApp emotionApp;

    @Test
    void doChatWithTools() {
        // 1. 测试 WebSearchTool (网页搜索)
        //testMessage("周末想带女朋友去北京约会，推荐几个适合情侣的小众打卡地？");

        // 2. 测试 WebScrapingTool (网页抓取)
        testMessage("最近情绪有点问题，看看心理网站（https://www.psy525.cn/）的其他人是怎么解决负面情绪的？");

        // 3. 测试 ResourceDownloadTool (资源下载)
        //testMessage("直接下载一张适合做手机壁纸的图片为文件，图片URL可以尝试使用 https://httpbin.org/image/jpeg ，保存为手机壁纸.jpg");

        // 4. 测试 FileOperationTool (文件操作)
        //testMessage("把我的心理档案（内容：我今天及时的完成了任务，没有拖延时间，非常开心！）保存为本地文件，文件名为 心理档案.txt");

        // 5. 测试 PDFGenerationTool (PDF生成)
        testMessage("生成一份‘七夕约会计划’PDF，文件名为 七夕约会计划.pdf，内容包含：1. 餐厅预订：外滩全景餐厅；2. 活动流程：下午去艺术展，晚上看夜景；3. 礼物清单：项链一根。");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        
        System.out.println("======================================");
        System.out.println("User: " + message);
        System.out.print("AI: ");
        
        // 收集流式输出的文本，并打印
        String answer = emotionApp.doChat(message, chatId)
                .doOnNext(System.out::print)
                .collectList()
                .block()
                .stream()
                .collect(Collectors.joining());
                
        System.out.println("\n======================================\n");
        
        // 验证返回结果不为空
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank());
    }
}
