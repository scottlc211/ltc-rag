package com.ltc.rag.domain;

/**
 * 一条可溯源的引用。
 *
 * <p>index 与答案正文里的 [1][2] 严格对应；url 用于回链原文（Git/Confluence/内网路径）。
 * 这是"给 Agent 用"和"给人看"的分水岭——没有结构的引用，Agent 无法校验答案。
 */
public record Citation(
        int index,
        long docId,
        long chunkId,
        String title,
        String headingPath,
        String url,
        double score,
        String snippet,
        Integer page
) {
}
