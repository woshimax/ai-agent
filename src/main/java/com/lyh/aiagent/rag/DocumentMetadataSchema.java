package com.lyh.aiagent.rag;

/**
 * 文档元数据字段定义
 * 字段名固定，字段值由LLM动态生成
 */
public class DocumentMetadataSchema {

    // === Spring AI 原生字段 ===
    public static final String EXCERPT_KEYWORDS = "excerpt_keywords";  // 关键词（逗号分隔）
    public static final String SECTION_SUMMARY = "section_summary";    // 章节摘要

    // === 自定义语义字段（LLM动态生成） ===
    public static final String TOPIC = "topic";                    // 主题分类（2-4个字）
    public static final String SUB_TOPICS = "sub_topics";          // 次要主题（逗号分隔）
    public static final String QUESTION_TYPE = "question_type";    // 内容类型

    // === 系统字段（自动生成） ===
    public static final String SOURCE = "source";                  // 文件名
    public static final String IMPORT_TIME = "import_time";        // 导入时间
    public static final String CHUNK_INDEX = "chunk_index";        // 分块索引
    public static final String CONTENT_LENGTH = "content_length";  // 内容长度
    public static final String FILE_TYPE = "file_type";            // 文件类型

    private DocumentMetadataSchema() {
        // 工具类，禁止实例化
    }
}
