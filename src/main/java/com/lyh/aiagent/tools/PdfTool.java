package com.lyh.aiagent.tools;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PdfTool {

    private static final Path BASE_DIR = Path.of("data", "tool-files", "pdfs").toAbsolutePath().normalize();

    @Tool(description = "使用给定的内容生成一个PDF文件并保存到本地。")
    public String generatePdf(
            @ToolParam(description = "要生成的PDF文件名，例如 report.pdf") String filename,
            @ToolParam(description = "要写入PDF文件的文本内容") String content) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("内容不能为空");
        }

        if (!filename.toLowerCase().endsWith(".pdf")) {
            filename += ".pdf";
        }

        Path targetPath = BASE_DIR.resolve(filename).normalize();
        if (!targetPath.startsWith(BASE_DIR)) {
            throw new IllegalArgumentException("不允许访问指定目录外的文件");
        }

        createParentDirectories(targetPath);

        try {
            File targetFile = targetPath.toFile();
            PdfWriter writer = new PdfWriter(targetFile);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);
            
            // 使用内置中文字体，防止中文乱码
            PdfFont font = PdfFontFactory.createFont("STSong-Light", "UniGB-UTF16-H", PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            document.setFont(font);
            
            document.add(new Paragraph(content));
            document.close();
            
            return "PDF文件生成成功: " + targetFile.getName() + "，路径: " + targetPath.toString();
        } catch (Exception e) {
            throw new IllegalStateException("生成PDF文件失败: " + filename, e);
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
