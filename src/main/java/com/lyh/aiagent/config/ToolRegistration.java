package com.lyh.aiagent.config;

import com.lyh.aiagent.tools.DownloadTool;
import com.lyh.aiagent.tools.FileTool;
import com.lyh.aiagent.tools.PdfTool;
import com.lyh.aiagent.tools.SearchTool;
import com.lyh.aiagent.tools.WebPageTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRegistration {

    @Bean
    public ToolCallback[] aiAgentTools(FileTool fileTool,
                                 SearchTool searchTool,
                                 WebPageTool webPageTool,
                                 DownloadTool downloadTool,
                                 PdfTool pdfTool) {
        return ToolCallbacks.from(
                fileTool,
                searchTool,
                webPageTool,
                downloadTool,
                pdfTool
        );
    }
}
