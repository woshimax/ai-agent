package com.lyh.aiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPageToolLiveTest {

    private final WebPageTool webPageTool = new WebPageTool(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void shouldFetchRealWebPage() throws Exception {
        String url = "https://www.runoob.com/";

        String result = webPageTool.fetchWebPage(url, "raw_html", 20000);
        System.out.println("=== WebPageTool Live Fetch Raw HTML Result ===");
        System.out.println(result);

        assertTrue(result.contains("<html"));
        assertTrue(result.contains("菜鸟教程") || result.contains("runoob"));
        assertTrue(result.length() > 1000);
    }
}
