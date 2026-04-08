package com.lyh.aiagent.tools;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileToolTest {

    private static final FileTool fileTool = new FileTool();
    private static final Path baseDir = Path.of("data", "tool-files");
    private static final String relativePath = "tests/file-tool.txt";
    private static final String content = "tool content————————test——————————write————————————";

//    @AfterAll
//    static void cleanup() throws Exception {
//        if (!Files.exists(baseDir)) {
//            return;
//        }
//        try (Stream<Path> pathStream = Files.walk(baseDir)) {
//            pathStream
//                    .sorted(Comparator.reverseOrder())
//                    .filter(path -> !path.equals(baseDir))
//                    .forEach(path -> {
//                        try {
//                            Files.deleteIfExists(path);
//                        } catch (Exception e) {
//                            throw new RuntimeException(e);
//                        }
//                    });
//        }
//    }

    @Test
    @Order(1)
    void shouldSaveFile() {
        String saveResult = fileTool.saveFile(relativePath, content);

        assertEquals("文件已保存: tests/file-tool.txt", saveResult);
    }

    @Test
    @Order(2)
    void shouldReadFile() {
        String readResult = fileTool.readFile(relativePath);

        assertEquals(content, readResult);
    }
}
