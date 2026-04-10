package com.lyh.aiagent.tools;

import cn.hutool.http.HttpUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DownloadTool {

    private static final Path BASE_DIR = Path.of("data", "tool-files", "downloads").toAbsolutePath().normalize();

    @Tool(description = "通过给定的URL下载网络资源文件到本地。")
    public String downloadFile(
            @ToolParam(description = "要下载的网络资源的URL地址") String url,
            @ToolParam(description = "本地保存的文件名，例如 image.png") String filename) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL不能为空");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        Path targetPath = BASE_DIR.resolve(filename).normalize();
        if (!targetPath.startsWith(BASE_DIR)) {
            throw new IllegalArgumentException("不允许访问指定目录外的文件");
        }

        createParentDirectories(targetPath);

        try {
            File targetFile = targetPath.toFile();
            long size = HttpUtil.downloadFile(url, targetFile);
            return "文件下载成功: " + targetFile.getName() + "，大小: " + size + " bytes";
        } catch (Exception e) {
            throw new IllegalStateException("下载文件失败: " + url, e);
        }
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
