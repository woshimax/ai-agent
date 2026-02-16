package com.lyh.aiagent.advisors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Flux;

//1、实现advisor
@Slf4j
public class LoggerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    //4、提供唯一名称
    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    //3、设置执行顺序
    @Override
    public int getOrder() {
        return 1;
    }

    private AdvisedRequest before(AdvisedRequest request) {
        log.info("AI Request: {}", request.userText());
        return request;
    }

    private void observeAfter(AdvisedResponse advisedResponse) {
        log.info("AI Response: {}", advisedResponse.response().getResult().getOutput().getText());
    }

    //2、实现方法

    //spring的advisor核心--around方法，用来构建调用、调用前、调用后的aop包围结构
    //由这个chain.nextAroundCall()往里走，最底层就是ChatModel进行AI调用
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);
        this.observeAfter(advisedResponse);
        return advisedResponse;
    }

    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        Flux<AdvisedResponse> advisedResponses = chain.nextAroundStream(advisedRequest);
        return (new MessageAggregator()).aggregateAdvisedResponse(advisedResponses, this::observeAfter);
    }

}
