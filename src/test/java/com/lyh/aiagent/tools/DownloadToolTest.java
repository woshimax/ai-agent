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

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DownloadToolTest {

    private static final DownloadTool downloadTool = new DownloadTool();
    private static final Path baseDir = Path.of("data", "tool-files", "downloads");
    private static final String testUrl = "https://www.w3schools.com/w3css/img_lights.jpg";
    private static final String testFilename = "img_lights.jpg";

    // @AfterAll
    // static void cleanup() throws Exception {
    //     if (!Files.exists(baseDir)) {
    //         return;
    //     }
    //     try (Stream<Path> pathStream = Files.walk(baseDir)) {
    //         pathStream
    //                 .sorted(Comparator.reverseOrder())
    //                 .filter(path -> !path.equals(baseDir))
    //                 .forEach(path -> {
    //                     try {
    //                         Files.deleteIfExists(path);
    //                     } catch (Exception e) {
    //                         throw new RuntimeException(e);
    //                     }
    //                 });
    //     }
    // }

    @Test
    @Order(1)
    void shouldDownloadFile() {
        String result = downloadTool.downloadFile(testUrl, testFilename);
        
        // 验证返回结果中包含成功关键字
        assertTrue(result.contains("文件下载成功"));
        assertTrue(result.contains(testFilename));
        
        // 验证文件确实被下载到了指定目录
        Path targetPath = baseDir.resolve(testFilename);
        assertTrue(Files.exists(targetPath));
        assertTrue(targetPath.toFile().length() > 0);
    }
}
