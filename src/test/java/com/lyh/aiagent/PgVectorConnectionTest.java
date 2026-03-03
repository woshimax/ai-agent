package com.lyh.aiagent;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PgVector 向量库基础操作测试
 * 目标库: ai_agent (PostgreSQL + pgvector)
 * 数据保留不删除，方便手动查看
 *
 * 通过注入 pgVectorJdbcTemplate，不再需要手动管理连接，
 * 连接池由 Spring 统一管理，和 MySQL 的使用方式一致。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgVectorConnectionTest {

    /** 测试用向量维度（小维度便于观察） */
    private static final int DIM = 3;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    /** 记录插入数据的 id，供后续 update 测试使用 */
    private static String insertedId;

    // ==================== 1. 基础连接 ====================

    @Test
    @Order(1)
    void testConnection() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT current_database(), version()");
        System.out.println("数据库: " + row.get("current_database"));
        System.out.println("版本: " + row.get("version"));

        String extVersion = jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String.class);
        assertNotNull(extVersion, "pgvector 扩展未安装");
        System.out.println("pgvector 版本: " + extVersion);
    }

    // ==================== 2. 建表 ====================

    @Test
    @Order(2)
    void testCreateTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS vector_items (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                content TEXT NOT NULL,
                metadata JSONB DEFAULT '{}',
                embedding vector(%d),
                created_at TIMESTAMP DEFAULT now()
            )
        """.formatted(DIM));

        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_name = 'vector_items'
            ORDER BY ordinal_position
        """);

        System.out.println("=== vector_items 表结构 ===");
        columns.forEach(col ->
                System.out.println("  " + col.get("column_name") + " : " + col.get("data_type")));
        assertTrue(columns.size() >= 4, "表应至少有 4 列");
        System.out.println("建表成功！");
    }

    // ==================== 3. 插入 ====================

    @Test
    @Order(3)
    void testInsert() {
        String sql = """
            INSERT INTO vector_items (id, content, metadata, embedding)
            VALUES (?::uuid, ?, ?::jsonb, ?::vector)
        """;

        String[][] data = {
            {"我今天心情很好，阳光明媚",       "[0.8, 0.9, 0.1]",  "{\"mood\": \"happy\"}"},
            {"最近工作压力有点大",             "[0.2, 0.1, 0.9]",  "{\"mood\": \"stressed\"}"},
            {"和朋友一起吃了顿火锅，很开心",    "[0.7, 0.85, 0.15]", "{\"mood\": \"happy\"}"},
            {"下雨天让人感觉有点忧郁",         "[0.3, 0.2, 0.8]",  "{\"mood\": \"sad\"}"},
            {"周末去爬山，呼吸新鲜空气真舒服",   "[0.75, 0.8, 0.2]", "{\"mood\": \"relaxed\"}"},
        };

        for (int i = 0; i < data.length; i++) {
            String id = UUID.randomUUID().toString();
            if (i == 0) insertedId = id;
            jdbcTemplate.update(sql, id, data[i][0], data[i][2], data[i][1]);
        }
        System.out.println("插入 " + data.length + " 条数据，首条 id=" + insertedId);
    }

    // ==================== 4. 查询 ====================

    @Test
    @Order(4)
    void testQuery() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, content, metadata, embedding, created_at
            FROM vector_items ORDER BY created_at
        """);

        System.out.println("=== 全量查询 ===");
        int count = 0;
        for (Map<String, Object> row : rows) {
            count++;
            System.out.println("#%d | content=%s | metadata=%s | embedding=%s".formatted(
                    count, row.get("content"), row.get("metadata"), row.get("embedding")));
        }
        assertTrue(count >= 5, "至少应有 5 条数据");
        System.out.println("共 " + count + " 条");
    }

    // ==================== 5. 相似度搜索 ====================

    @Test
    @Order(5)
    void testCosineSimilaritySearch() {
        String queryVector = "[0.8, 0.9, 0.1]";
        String sql = """
            SELECT content, metadata,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM vector_items
            ORDER BY embedding <=> ?::vector
            LIMIT 3
        """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, queryVector, queryVector);

        System.out.println("=== 余弦相似度搜索 (查 '开心' 方向) ===");
        int rank = 0;
        for (Map<String, Object> row : rows) {
            rank++;
            System.out.println("#%d | sim=%.4f | %s | %s".formatted(
                    rank, ((Number) row.get("similarity")).doubleValue(),
                    row.get("content"), row.get("metadata")));
        }
        assertTrue(rank > 0);
    }

    @Test
    @Order(6)
    void testL2DistanceSearch() {
        String queryVector = "[0.2, 0.1, 0.9]";
        String sql = """
            SELECT content, metadata,
                   embedding <-> ?::vector AS l2_distance
            FROM vector_items
            ORDER BY embedding <-> ?::vector
            LIMIT 3
        """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, queryVector, queryVector);

        System.out.println("=== L2 距离搜索 (查 '压力' 方向) ===");
        for (Map<String, Object> row : rows) {
            System.out.println("dist=%.4f | %s".formatted(
                    ((Number) row.get("l2_distance")).doubleValue(),
                    row.get("content")));
        }
    }

    // ==================== 6. 带条件的相似度搜索 ====================

    @Test
    @Order(7)
    void testFilteredSimilaritySearch() {
        String queryVector = "[0.7, 0.8, 0.2]";
        String sql = """
            SELECT content, metadata,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM vector_items
            WHERE metadata->>'mood' = 'happy'
            ORDER BY embedding <=> ?::vector
        """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, queryVector, queryVector);

        System.out.println("=== 带 metadata 过滤的搜索 (mood=happy) ===");
        for (Map<String, Object> row : rows) {
            System.out.println("sim=%.4f | %s".formatted(
                    ((Number) row.get("similarity")).doubleValue(),
                    row.get("content")));
        }
        assertFalse(rows.isEmpty(), "应至少有 happy 的数据");
    }

    // ==================== 7. 更新 ====================

    @Test
    @Order(8)
    void testUpdate() {
        assertNotNull(insertedId, "insertedId 不应为空，请先运行 testInsert");

        String sql = """
            UPDATE vector_items
            SET content = ?, embedding = ?::vector, metadata = ?::jsonb
            WHERE id = ?::uuid
        """;
        int updated = jdbcTemplate.update(sql,
                "今天被表扬了，心情超级好！",
                "[0.9, 0.95, 0.05]",
                "{\"mood\": \"ecstatic\"}",
                insertedId);
        assertEquals(1, updated);

        // 验证
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, embedding, metadata FROM vector_items WHERE id = ?::uuid",
                insertedId);
        System.out.println("更新后: content=%s | embedding=%s | metadata=%s".formatted(
                row.get("content"), row.get("embedding"), row.get("metadata")));
        System.out.println("更新成功！");
    }

    // ==================== 8. 统计 ====================

    @Test
    @Order(9)
    void testStats() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_items", Integer.class);
        System.out.println("总数据量: " + total);

        List<Map<String, Object>> stats = jdbcTemplate.queryForList("""
            SELECT metadata->>'mood' AS mood, COUNT(*) AS cnt
            FROM vector_items
            GROUP BY metadata->>'mood'
            ORDER BY cnt DESC
        """);

        System.out.println("=== 按 mood 分组 ===");
        stats.forEach(row ->
                System.out.println("  " + row.get("mood") + ": " + row.get("cnt") + " 条"));
    }
}
