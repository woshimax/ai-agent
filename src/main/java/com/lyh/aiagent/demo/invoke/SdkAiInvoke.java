package com.lyh.aiagent.demo.invoke;

import java.util.Arrays;
import java.lang.System;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.utils.JsonUtils;
import com.lyh.aiagent.AiAgentApplication;
import com.lyh.aiagent.config.DashscopeProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@Data
public class SdkAiInvoke {
    @Autowired
    private final DashscopeProperties dashscopeProperties;

    public GenerationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException {
        // 各地域的base_url不同
        Generation gen = new Generation(Protocol.HTTP.getValue(), "https://dashscope.aliyuncs.com/api/v1");
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("You are a helpful assistant.")
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content("你是谁？")
                .build();

        String apiKey = dashscopeProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new NoApiKeyException();
        }

        GenerationParam param = GenerationParam.builder()
                // 若没有配置环境变量，请用阿里云百炼API Key将下行替换为：.apiKey("sk-xxx")
                // 各地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
                .apiKey(apiKey)
                .model("qwen-plus")
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        return gen.call(param);
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(AiAgentApplication.class, args)) {
            GenerationResult result = ctx.getBean(SdkAiInvoke.class).callWithMessage();
            System.out.println(result.getOutput().getChoices().get(0).getMessage().getContent());
            // 如需查看完整响应，请取消下列注释
            // System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            System.err.println("错误信息：" + e.getMessage());
            System.out.println("请参考文档：https://help.aliyun.com/zh/model-studio/developer-reference/error-code");
        }
    }
}