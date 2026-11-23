package com.ltc.rag.chunk;

import com.ltc.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static RagProperties props(int maxChild, int overlap, int maxParent) {
        return new RagProperties(
                "ltc",
                new RagProperties.Auth(true),
                new RagProperties.Chunking(maxChild, overlap, maxParent),
                new RagProperties.Retrieval(30, 30, 6, 60, 0.15),
                new RagProperties.Embedding(true, "http://localhost", "k", "m", 3, 10, 5),
                new RagProperties.Chat("http://localhost", "k", "m", 0.2, 100, 5),
                new RagProperties.Rerank(false, "http://localhost", "k", "m", 5),
                new RagProperties.Ocr(true, "http://localhost:8091"),
                new RagProperties.Storage(true, "http://localhost", "c"),
                new RagProperties.Callback(5, 10, 10, false));
    }

    @Test
    void 短章节直接成为单个子块并带上标题路径() {
        Chunker chunker = new Chunker(props(200, 0, 400));
        List<ChunkPiece> pieces = chunker.split("""
                # 接口规范
                ## 创建订单
                调用 POST /api/v2/order/create，必填字段 user_id。
                """);

        assertThat(pieces).hasSize(1);
        ChunkPiece only = pieces.get(0);
        assertThat(only.type()).isEqualTo(ChunkPiece.CHILD);
        assertThat(only.headingPath()).isEqualTo("接口规范 > 创建订单");
        // 标题路径必须并入正文，否则检索命中后模型不知道这段属于哪个模块
        assertThat(only.content()).startsWith("接口规范 > 创建订单");
        assertThat(only.content()).contains("POST /api/v2/order/create");
    }

    @Test
    void 长章节生成父块且子块回指父块() {
        String body = "这是很长的说明文字。".repeat(60);
        Chunker chunker = new Chunker(props(200, 20, 400));
        List<ChunkPiece> pieces = chunker.split("# 部署\n" + body);

        List<ChunkPiece> parents = pieces.stream().filter(ChunkPiece::isParent).toList();
        List<ChunkPiece> children = pieces.stream().filter(p -> !p.isParent()).toList();

        assertThat(parents).hasSize(1);
        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(child -> {
            assertThat(child.parentIndex()).isEqualTo(parents.get(0).index());
            assertThat(child.charCount()).isLessThanOrEqualTo(200);
        });
        // 父块 index 必须小于子块，入库时才能先插父再插子
        assertThat(parents.get(0).index()).isLessThan(children.get(0).index());
    }

    @Test
    void 代码块里的井号不会被误判成标题() {
        Chunker chunker = new Chunker(props(400, 0, 800));
        List<ChunkPiece> pieces = chunker.split("""
                # 配置示例
                ```yaml
                # 这是注释，不是标题
                server:
                  port: 8080
                ```
                """);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).headingPath()).isEqualTo("配置示例");
        assertThat(pieces.get(0).content()).contains("# 这是注释，不是标题");
    }

    @Test
    void 空文档产出空列表而不是抛异常() {
        Chunker chunker = new Chunker(props(200, 0, 400));
        assertThat(chunker.split("   \n\n  ")).isEmpty();
    }
}
