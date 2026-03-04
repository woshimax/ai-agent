package com.lyh.aiagent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeImportRunner implements ApplicationRunner {

    private final KnowledgeImportService knowledgeImportService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始扫描并导入知识库文件...");
        knowledgeImportService.importNewFiles();
        log.info("知识库文件导入完成");
    }
}
