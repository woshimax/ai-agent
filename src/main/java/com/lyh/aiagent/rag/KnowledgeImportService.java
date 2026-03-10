package com.lyh.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.KeywordMetadataEnricher;
import org.springframework.ai.transformer.SummaryMetadataEnricher;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.lyh.aiagent.rag.DocumentMetadataSchema.*;
import static org.springframework.ai.transformer.SummaryMetadataEnricher.SummaryType;

@Slf4j
@Service
public class KnowledgeImportService {

    private final VectorStore vectorStore;
    private final JdbcTemplate pgJdbcTemplate;
    private final Path knowledgeDir;

    // 元数据增强器
    private final KeywordMetadataEnricher keywordEnricher;
    private final SummaryMetadataEnricher summaryEnricher;
    private final AdaptiveTopicEnricher topicEnricher;

    public KnowledgeImportService(
            VectorStore vectorStore,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate,
            @Qualifier("dashscopeChatModel") ChatModel chatModel,
            @Value("${aiagent.rag.knowledge-dir:knowledge}") String knowledgeDir) {
        this.vectorStore = vectorStore;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.knowledgeDir = Path.of(knowledgeDir);

        // 初始化 Spring AI 原生元数据增强器
        this.keywordEnricher = new KeywordMetadataEnricher(chatModel, 8);  // 提取8个关键词

        this.summaryEnricher = new SummaryMetadataEnricher(
                chatModel,
                List.of(SummaryType.CURRENT)  // 只生成当前文档摘要
        );

        // 初始化自定义自适应主题分类器
        this.topicEnricher = new AdaptiveTopicEnricher(chatModel, pgJdbcTemplate);

        log.info("知识库元数据增强器已初始化：关键词提取、摘要生成、自适应主题分类");
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
            List<Document> chunks = splitBySection(content, source, file);

            log.info("开始元数据增强: {}, 分块数: {}", source, chunks.size());

            // 🎯 元数据增强流水线
            chunks = enrichMetadata(chunks);

            log.info("元数据增强完成，开始向量化导入: {}", source);

            // DashScope embedding API 单次最多 10 条，分批发送
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                List<Document> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
                vectorStore.add(batch);
            }

            log.info("✅ 成功导入文件: {}, 分块数: {}", source, chunks.size());
        } catch (Exception e) {
            log.error("❌ 导入文件失败: {}", source, e);
        }
    }

    /**
     * 元数据增强流水线
     * 1. Spring AI 原生：关键词提取
     * 2. Spring AI 原生：摘要生成
     * 3. 自定义：自适应主题分类
     * 4. 系统元数据：时间、索引等
     */
    private List<Document> enrichMetadata(List<Document> documents) {
        try {
            // 1. 提取关键词（Spring AI）
            log.debug("提取关键词...");
            documents = keywordEnricher.apply(documents);

            // 2. 生成摘要（Spring AI）
            log.debug("生成摘要...");
            documents = summaryEnricher.apply(documents);

            // 3. 自适应主题分类（自定义）
            log.debug("主题分类...");
            documents = topicEnricher.apply(documents);

            // 4. 添加系统元数据
            log.debug("添加系统元数据...");
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                doc.getMetadata().put(CHUNK_INDEX, i);
                doc.getMetadata().put(IMPORT_TIME, LocalDateTime.now().toString());
                doc.getMetadata().put(CONTENT_LENGTH, doc.getText().length());
            }

            return documents;
        } catch (Exception e) {
            log.error("元数据增强失败，使用基础元数据继续导入", e);
            return documents;
        }
    }

    private static final Pattern SECTION_SPLIT = Pattern.compile("(?=\n#{1,4} )");

    private List<Document> splitBySection(String content, String source, Path file) {
        String[] sections = SECTION_SPLIT.split(content);
        List<Document> docs = new ArrayList<>();

        for (String section : sections) {
            String trimmed = section.strip();
            if (trimmed.isEmpty() || !trimmed.contains("\n")) continue;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SOURCE, source);
            metadata.put(FILE_TYPE, getFileExtension(file));

            docs.add(new Document(trimmed, metadata));
        }

        if (docs.isEmpty()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SOURCE, source);
            metadata.put(FILE_TYPE, getFileExtension(file));
            docs.add(new Document(content.strip(), metadata));
        }

        return docs;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex + 1).toLowerCase() : "";
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
