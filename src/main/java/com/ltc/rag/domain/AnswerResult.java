package com.ltc.rag.domain;

import java.util.List;

/**
 * 一次问答的结果。
 *
 * @param grounded 是否检索到了足够相关的片段；false 时答案只能是"未找到"，
 *                 不能退回模型自身知识（那是幻觉的温床）
 */
public record AnswerResult(
        String answer,
        List<Citation> citations,
        boolean grounded,
        Stats stats
) {

    public record Stats(
            int hits,
            long retrievalMs,
            long llmMs,
            Integer promptTokens,
            Integer completionTokens
    ) {
    }
}
