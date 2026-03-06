package com.lyh.aiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyh.aiagent.app.EmotionApp;
import com.lyh.aiagent.app.EmotionReport;
import com.lyh.aiagent.mapper.ReportMapper;
import com.lyh.aiagent.model.Report;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final ReportMapper reportMapper;
    private final EmotionApp emotionApp;
    private final ObjectMapper objectMapper;

    /**
     * 获取报告：先查库，没有则调 LLM 生成并存库
     */
    public EmotionReport getOrGenerate(String chatId) {
        Report report = reportMapper.selectOne(
                new QueryWrapper<Report>().eq("chat_id", chatId)
        );
        if (report != null) {
            try {
                return objectMapper.readValue(report.getContent(), EmotionReport.class);
            } catch (Exception e) {
                log.warn("解析报告JSON失败，重新生成: {}", e.getMessage());
            }
        }

        // 调 LLM 生成
        EmotionReport emotionReport = emotionApp.generateReport(chatId);

        // 存库
        try {
            Report newReport = new Report();
            newReport.setChatId(chatId);
            newReport.setContent(objectMapper.writeValueAsString(emotionReport));
            newReport.setCreateTime(new Date());
            newReport.setUpdateTime(new Date());
            reportMapper.insert(newReport);
        } catch (Exception e) {
            log.warn("保存报告失败: {}", e.getMessage());
        }

        return emotionReport;
    }
}
