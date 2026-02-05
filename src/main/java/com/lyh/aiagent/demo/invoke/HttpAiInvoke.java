package com.lyh.aiagent.demo.invoke;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.lyh.aiagent.AiAgentApplication;
import com.lyh.aiagent.config.DashscopeProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;
@Component
@Data
public class HttpAiInvoke {
    @Autowired
    private final DashscopeProperties dashscopeProperties;

    public String call(){
        return dashscopeProperties.getApiKey();
    }
    public static void main(String[] args) {
        String ApiKey;
        try (ConfigurableApplicationContext ctx = SpringApplication.run(AiAgentApplication.class, args)) {
            HttpAiInvoke invoke = ctx.getBean(HttpAiInvoke.class);
            // 在 HttpAiInvoke 里用 @Autowired 的 DashscopeProperties
            ApiKey = invoke.call();
        }
        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";


        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + ApiKey);
        headers.put("Content-Type", "application/json");


        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "qwen-plus");

        JSONObject input = new JSONObject();
        JSONObject[] messages = new JSONObject[2];

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "You are a helpful assistant.");
        messages[0] = systemMessage;

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", "你是谁？");
        messages[1] = userMessage;

        input.put("messages", messages);
        requestBody.put("input", input);

        JSONObject parameters = new JSONObject();
        parameters.put("result_format", "message");
        requestBody.put("parameters", parameters);


        HttpResponse response = HttpRequest.post(url)
                .addHeaders(headers)
                .body(requestBody.toString())
                .execute();


        if (response.isOk()) {
            System.out.println("请求成功，响应内容：");
            System.out.println(response.body());
        } else {
            System.out.println("请求失败，状态码：" + response.getStatus());
            System.out.println("响应内容：" + response.body());
        }
    }
}
