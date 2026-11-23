package com.ltc.rag.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装。
 *
 * <p>三条硬约束，都是为了"可验证"：
 * <ol>
 *   <li>只依据资料，不允许模型自由发挥；</li>
 *   <li>必须用 [n] 标注，且 n 只能来自资料编号（我们随后会把 [n] 映射回 citations）；</li>
 *   <li>没有资料就直说没有，而不是编一个看起来合理的答案。</li>
 * </ol>
 */
@Component
public class PromptBuilder {

    public record Prompt(String system, String user) {
    }

    private static final String SYSTEM = """
            你是 LTC 项目的知识库助手，服务于研发与集成方。
            只能依据【参考资料】回答，禁止使用模型自身知识进行补充。
            规则：
            1. 每个关键结论后用 [编号] 标注来源，编号必须是【参考资料】里出现过的编号。
            2. 如果资料不足以回答，直接说明"根据现有文档未找到相关内容"，并指出缺少哪类信息；不要猜测。
            3. 涉及接口、字段、配置项时保留原文的命名与大小写。
            4. 用中文回答，结构清晰，必要时分点。
            """;

    public Prompt build(String question, List<Retriever.Candidate> candidates) {
        StringBuilder user = new StringBuilder("【参考资料】\n");
        int index = 1;
        for (Retriever.Candidate candidate : candidates) {
            user.append('[').append(index++).append("] 《")
                    .append(candidate.title()).append('》');
            if (!candidate.headingPath().isBlank()) {
                user.append(" — ").append(candidate.headingPath());
            }
            user.append('\n').append(candidate.content()).append("\n\n");
        }
        user.append("【问题】\n").append(question);
        return new Prompt(SYSTEM, user.toString());
    }
}
