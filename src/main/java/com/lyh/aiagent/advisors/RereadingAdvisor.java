package com.lyh.aiagent.advisors;

import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;


public class RereadingAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    /**
     * 本质：请求拦截器，使用AdviesedRequest再封装一点信息（reread信息），底层就是chatclient会再原问题基础上再思考一次
     * @param advisedRequest
     * @return
     */
    private AdvisedRequest before(AdvisedRequest advisedRequest) {
        String originalQuery = advisedRequest.userText();
        return AdvisedRequest.from(advisedRequest)
                .userText(originalQuery + "\nRead the question again: " + originalQuery)
                .build();
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        return chain.nextAroundCall(this.before(advisedRequest));
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(this.before(advisedRequest));
    }

    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }
}
