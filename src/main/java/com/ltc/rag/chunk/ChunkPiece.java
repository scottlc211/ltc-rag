package com.ltc.rag.chunk;

/**
 * 一个待入库的文本块。
 *
 * @param type          PARENT / CHILD / CODE / TABLE
 * @param index         文档内顺序号（等于最终列表下标，便于子块回指父块）
 * @param parentIndex   子块指向的父块 index，父块为 null
 * @param headingPath   标题路径，如 "接口规范 > 订单 > 创建订单"
 * @param content       真正入库/送进 prompt 的文本（标题路径 + 正文）
 * @param charCount     字符数（中文场景下近似 token 数，用于后续预算控制）
 * @param contentHash   content 的 SHA256，用于增量更新时原位复用 Qdrant vector_id
 * @param pageNo        PDF 起始页码（从 1 开始），非 PDF 文档为 null
 */
public record ChunkPiece(
        String type,
        int index,
        Integer parentIndex,
        String headingPath,
        String content,
        int charCount,
        String contentHash,
        Integer pageNo
) {

    public static final String PARENT = "PARENT";
    public static final String CHILD = "CHILD";
    public static final String CODE = "CODE";
    public static final String TABLE = "TABLE";

    public boolean isParent() {
        return PARENT.equals(type);
    }
}
