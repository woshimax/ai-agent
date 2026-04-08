package com.lyh.aiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class WebPageTool {

    private static final int TIMEOUT_MILLIS = 10000;
    private static final int DEFAULT_MAX_LENGTH = 4000;
    private static final int MAX_ALLOWED_LENGTH = 20000;
    private static final int MAX_LINKS = 8;
    private static final String DEFAULT_FORMAT = "structured";
    private static final String MAIN_CONTENT_SELECTORS =
            "main, article, [role=main], .main, #main, .content, #content, .article, .post, .entry-content";
    private static final String NOISE_SELECTORS =
            "script, style, noscript, svg, iframe, canvas, form, button, input, textarea, select, option, " +
                    "nav, footer, header, aside, .sidebar, .footer, .header, .nav, .ads, .advertisement";

    private final ObjectMapper objectMapper;

    public WebPageTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fetchWebPage(String url) {
        return fetchWebPage(url, DEFAULT_FORMAT, DEFAULT_MAX_LENGTH);
    }

    public String fetchWebPage(String url, String format) {
        return fetchWebPage(url, format, DEFAULT_MAX_LENGTH);
    }

    @Tool(description = "抓取网页并返回适合大模型读取的内容。format 可选：structured、text、html、raw_html。默认 structured，适合工具调用；html 返回清洗后的主内容 HTML；raw_html 返回完整源码，内容较大，仅在需要 DOM 细节时使用。")
    public String fetchWebPage(
            @ToolParam(description = "完整网页 URL，例如 https://example.com") String url,
            @ToolParam(description = "返回格式，可选值：structured、text、html、raw_html；为空时默认 structured") String format,
            @ToolParam(description = "返回内容最大长度，默认 4000，最大 20000") Integer maxLength) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空");
        }

        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(TIMEOUT_MILLIS)
                    .get();
            return formatDocument(url, document, format, maxLength);
        } catch (Exception e) {
            throw new IllegalStateException("抓取网页失败: " + url, e);
        }
    }

    String formatDocument(String url, Document document) {
        return formatDocument(url, document, DEFAULT_FORMAT, DEFAULT_MAX_LENGTH);
    }

    String formatDocument(String url, Document document, String format) {
        return formatDocument(url, document, format, DEFAULT_MAX_LENGTH);
    }

    String formatDocument(String url, Document document, String format, Integer maxLength) {
        String normalizedFormat = normalizeFormat(format);
        int safeMaxLength = normalizeMaxLength(maxLength);
        Document cleanedDocument = cleanDocument(document);
        Element mainContent = selectMainContent(cleanedDocument);

        return switch (normalizedFormat) {
            case "structured" -> formatStructuredDocument(url, cleanedDocument, mainContent, safeMaxLength);
            case "text" -> abbreviate(extractText(mainContent), safeMaxLength);
            case "html" -> abbreviate(mainContent.outerHtml(), safeMaxLength);
            case "raw_html" -> abbreviate(document.outerHtml(), safeMaxLength);
            default -> throw new IllegalArgumentException("不支持的 format: " + format);
        };
    }

    private String formatStructuredDocument(String url, Document cleanedDocument, Element mainContent, int maxLength) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("url", url);

        String title = cleanedDocument.title();
        if (StringUtils.hasText(title)) {
            result.put("title", title);
        }

        String content = abbreviate(extractText(mainContent), maxLength);
        if (StringUtils.hasText(content)) {
            result.put("content", content);
        }

        ArrayNode links = objectMapper.createArrayNode();
        Set<String> seenLinks = new LinkedHashSet<>();
        for (Element link : mainContent.select("a[href]")) {
            String href = link.absUrl("href");
            if (!StringUtils.hasText(href)) {
                href = link.attr("href");
            }
            if (!isUsefulLink(href) || !seenLinks.add(href)) {
                continue;
            }

            ObjectNode item = objectMapper.createObjectNode();
            item.put("href", href);

            String linkText = normalizeWhitespace(link.text());
            if (StringUtils.hasText(linkText)) {
                item.put("text", abbreviate(linkText, 120));
            }
            links.add(item);

            if (links.size() >= MAX_LINKS) {
                break;
            }
        }
        if (!links.isEmpty()) {
            result.set("links", links);
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("生成网页解析结果失败", e);
        }
    }

    private Document cleanDocument(Document document) {
        Document cleaned = document.clone();
        cleaned.select(NOISE_SELECTORS).remove();
        return cleaned;
    }

    private Element selectMainContent(Document document) {
        Element best = null;
        int bestLength = -1;

        for (Element candidate : document.select(MAIN_CONTENT_SELECTORS)) {
            int candidateLength = normalizeWhitespace(candidate.text()).length();
            if (candidateLength > bestLength) {
                best = candidate;
                bestLength = candidateLength;
            }
        }

        if (best != null) {
            return best;
        }
        return document.body() != null ? document.body() : document;
    }

    private String extractText(Element element) {
        return normalizeWhitespace(element.text());
    }

    private boolean isUsefulLink(String href) {
        if (!StringUtils.hasText(href)) {
            return false;
        }
        String normalizedHref = href.trim().toLowerCase();
        return !normalizedHref.startsWith("#")
                && !normalizedHref.startsWith("javascript:")
                && !normalizedHref.startsWith("mailto:");
    }

    private String normalizeFormat(String format) {
        if (!StringUtils.hasText(format)) {
            return DEFAULT_FORMAT;
        }
        return format.trim().toLowerCase();
    }

    private int normalizeMaxLength(Integer maxLength) {
        if (maxLength == null || maxLength <= 0) {
            return DEFAULT_MAX_LENGTH;
        }
        return Math.min(maxLength, MAX_ALLOWED_LENGTH);
    }

    private String normalizeWhitespace(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
