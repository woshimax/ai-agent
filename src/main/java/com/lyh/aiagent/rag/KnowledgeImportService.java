package com.lyh.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class KnowledgeImportService {

    private final VectorStore vectorStore;
    private final JdbcTemplate pgJdbcTemplate;
    private final Path knowledgeDir;

    public KnowledgeImportService(
            VectorStore vectorStore,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate,
            @Value("${aiagent.rag.knowledge-dir:knowledge}") String knowledgeDir) {
        this.vectorStore = vectorStore;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.knowledgeDir = Path.of(knowledgeDir);
    }

    public void importNewFiles() {
        if (!Files.isDirectory(knowledgeDir)) {
            log.warn("知识库目录不存在: {}", knowledgeDir.toAbsolutePath());
            return;
        }

        Set<String> importedSources = getImportedSources();

        try (Stream<Path> paths = Files.list(knowledgeDir)) {
            List<Path> files = paths
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".txt") || name.endsWith(".md");
                    })
                    .toList();

            for (Path file : files) {
                String source = file.getFileName().toString();
                if (importedSources.contains(source)) {
                    log.info("跳过已导入文件: {}", source);
                    continue;
                }
                importFile(file, source);
            }
        } catch (IOException e) {
            log.error("扫描知识库目录失败", e);
        }
    }

    /**
     * 按 Markdown #### 标题分块，保证每个 Q&A 完整不被切断。
     * 没有 #### 标题的文件整体作为一个 Document。
     */
    private static final int BATCH_SIZE = 10;

    private void importFile(Path file, String source) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            List<Document> chunks = splitBySection(content, source);
            // DashScope embedding API 单次最多 10 条，分批发送
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                List<Document> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
                vectorStore.add(batch);
            }
            log.info("成功导入文件: {}, 分块数: {}", source, chunks.size());
        } catch (Exception e) {
            log.error("导入文件失败: {}", source, e);
        }
    }

    private static final Pattern SECTION_SPLIT = Pattern.compile("(?=\n#{1,4} )");

    private List<Document> splitBySection(String content, String source) {
        String[] sections = SECTION_SPLIT.split(content);
        List<Document> docs = new ArrayList<>();
        for (String section : sections) {
            String trimmed = section.strip();
            if (trimmed.isEmpty() || !trimmed.contains("\n")) continue;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);
            docs.add(new Document(trimmed, metadata));
        }
        if (docs.isEmpty()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);
            docs.add(new Document(content.strip(), metadata));
        }
        return docs;
    }

    private Set<String> getImportedSources() {
        try {
            List<String> sources = pgJdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'source' FROM vector_store WHERE metadata->>'source' IS NOT NULL",
                    String.class);
            return Set.copyOf(sources);
        } catch (Exception e) {
            log.debug("查询已导入文件列表失败（表可能尚未创建）: {}", e.getMessage());
            return Set.of();
        }
    }
}
