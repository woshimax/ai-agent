package com.lyh.aiagent.app;

import java.util.List;

public record EmotionReport(
        String title,
        List<String> problems,
        String emotionState,
        List<String> shortTermAdvice,
        List<String> longTermAdvice
) {
}
