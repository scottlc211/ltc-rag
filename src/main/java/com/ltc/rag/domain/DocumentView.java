package com.ltc.rag.domain;

import java.time.LocalDateTime;

/**
 * 对外的文档视图（不含内部 hash 等细节）。
 */
public record DocumentView(
        long id,
        String namespace,
        String docKey,
        String title,
        String format,
        int version,
        String status,
        int chunkCount,
        int charCount,
        String originUrl,
        String error,
        LocalDateTime updatedAt
) {
}
