package com.lyh.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

@Component
public class FileTool {

    private static final Path BASE_DIR = Path.of("data", "tool-files").toAbsolutePath().normalize();

    @Tool(description = "保存文本内容到文件。路径只能是 data/tool-files 目录下的相对路径。")
    public String saveFile(
            @ToolParam(description = "data/tool-files 目录下的相对文件路径，例如 notes/todo.txt") String relativePath,
            @ToolParam(description = "要保存的文本内容") String content) {
        Path targetPath = resolvePath(relativePath);
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        createParentDirectories(targetPath);
        try {
            Files.writeString(targetPath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存文件失败: " + relativePath, e);
        }
        return "文件已保存: " + BASE_DIR.relativize(targetPath);
    }

    @Tool(description = "读取文本文件内容。路径只能是 data/tool-files 目录下的相对路径。")
    public String readFile(
            @ToolParam(description = "data/tool-files 目录下的相对文件路径，例如 notes/todo.txt") String relativePath) {
        Path targetPath = resolvePath(relativePath);
        try {
            return Files.readString(targetPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + relativePath, e);
        }
    }

    private Path resolvePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        Path normalizedPath = BASE_DIR.resolve(relativePath).normalize();
        if (!normalizedPath.startsWith(BASE_DIR)) {
            throw new IllegalArgumentException("不允许访问 data/tool-files 目录外的文件");
        }
        return normalizedPath;
    }

    private void createParentDirectories(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("创建目录失败: " + parent, e);
        }
    }
}
