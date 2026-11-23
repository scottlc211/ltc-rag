package com.ltc.rag.api;

import com.ltc.rag.domain.DocumentView;
import jakarta.validation.constraints.NotBlank;

/**
 * 请求/响应体集中定义，避免一堆只有两个字段的 DTO 文件。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record TextIngestRequest(
            @NotBlank(message = "content 不能为空") String content,
            String namespace,
            String docKey,
            String title,
            String url
    ) {
    }

    public record IngestResponse(DocumentView document, boolean created, boolean changed) {
    }

    public record DeleteResponse(long id, int deletedVectors) {
    }

    /** 在线编辑文档正文：content 是完整 Markdown，title 为空则沿用原标题。 */
    public record TextContentRequest(
            @NotBlank(message = "content 不能为空") String content,
            String title
    ) {
    }
}
