package com.ltc.rag.service;

import com.ltc.rag.config.RagProperties;
import com.ltc.rag.domain.AnswerResult;
import com.ltc.rag.domain.Citation;
import com.ltc.rag.llm.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 问答编排：检索 → 组装 Prompt → 生成 → 产出结构化引用。
 *
 * <p>关键取舍：<b>检索不到就不让模型说话</b>。
 * 先返回"未找到"比让模型用自身知识编一段更像专业答案，对内部知识库来说后者危害大得多。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    public static final String NO_CONTEXT_ANSWER =
            "根据现有文档未找到相关内容。建议：确认该模块文档已入库，或使用文档里的原术语（如接口名、错误码）重新提问。";

    private static final int SNIPPET_CHARS = 200;

    private final Retriever retriever;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final RagProperties props;

    public QaService(Retriever retriever, PromptBuilder promptBuilder,
                     ChatClient chatClient, RagProperties props) {
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClient;
        this.props = props;
    }

    public AnswerResult answer(String question, List<String> namespaces) {
        return answer(question, namespaces, null, null);
    }

    public AnswerResult answer(String question, List<String> namespaces, Integer topN) {
        return answer(question, namespaces, topN, null);
    }

    /**
     * @param topN  调用方指定上下文条数，null 用默认配置（上限 12，防止 prompt 爆炸）
     * @param model 调用方单次临时指定的 CHAT_MODEL，null 则用全局生效模型
     */
    public AnswerResult answer(String question, List<String> namespaces, Integer topN, String model) {
        long start = System.currentTimeMillis();
        List<Retriever.Candidate> candidates = retriever.retrieve(
                question, namespaces, contextTopN(topN));
        long retrievalMs = System.currentTimeMillis() - start;

        if (candidates.isEmpty()) {
            log.info("未检索到相关内容 question={}", question);
            return new AnswerResult(NO_CONTEXT_ANSWER, List.of(), false,
                    new AnswerResult.Stats(0, retrievalMs, 0, null, null));
        }

        PromptBuilder.Prompt prompt = promptBuilder.build(question, candidates);
        long llmStart = System.currentTimeMillis();
        ChatClient.Reply reply = chatClient.complete(prompt.system(), prompt.user(), model);
        long llmMs = System.currentTimeMillis() - llmStart;

        return new AnswerResult(reply.content(), toCitations(candidates), true,
                new AnswerResult.Stats(candidates.size(), retrievalMs, llmMs,
                        reply.promptTokens(), reply.completionTokens()));
    }

    public AnswerResult streamAnswer(String question, List<String> namespaces, Consumer<String> onToken) {
        return streamAnswer(question, namespaces, null, null, onToken);
    }

    public AnswerResult streamAnswer(String question, List<String> namespaces, Integer topN,
                                     Consumer<String> onToken) {
        return streamAnswer(question, namespaces, topN, null, onToken);
    }

    /**
     * 流式版本：检索仍是同步的（先拿到引用再开流，保证 [n] 与 citations 对齐），
     * 生成阶段逐 token 回调。
     */
    public AnswerResult streamAnswer(String question, List<String> namespaces, Integer topN, String model,
                                     Consumer<String> onToken) {
        long start = System.currentTimeMillis();
        List<Retriever.Candidate> candidates = retriever.retrieve(
                question, namespaces, contextTopN(topN));
        long retrievalMs = System.currentTimeMillis() - start;

        if (candidates.isEmpty()) {
            onToken.accept(NO_CONTEXT_ANSWER);
            return new AnswerResult(NO_CONTEXT_ANSWER, List.of(), false,
                    new AnswerResult.Stats(0, retrievalMs, 0, null, null));
        }

        PromptBuilder.Prompt prompt = promptBuilder.build(question, candidates);
        long llmStart = System.currentTimeMillis();
        ChatClient.Reply reply = chatClient.stream(prompt.system(), prompt.user(), model, onToken);
        long llmMs = System.currentTimeMillis() - llmStart;

        return new AnswerResult(reply.content(), toCitations(candidates), true,
                new AnswerResult.Stats(candidates.size(), retrievalMs, llmMs,
                        reply.promptTokens(), reply.completionTokens()));
    }

    private int contextTopN(Integer requested) {
        int fallback = props.retrieval().contextTopN();
        if (requested == null) {
            return fallback;
        }
        return Math.max(1, Math.min(requested, 12));
    }

    private static List<Citation> toCitations(List<Retriever.Candidate> candidates) {
        List<Citation> citations = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Retriever.Candidate candidate = candidates.get(i);
            citations.add(new Citation(
                    i + 1,
                    candidate.docId(),
                    candidate.chunkId(),
                    candidate.title(),
                    candidate.headingPath(),
                    candidate.url(),
                    candidate.score(),
                    snippet(candidate.content())));
        }
        return citations;
    }

    private static String snippet(String content) {
        String flat = content.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }
}
