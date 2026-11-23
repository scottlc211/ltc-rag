"""
最小可用的 OpenAI 兼容 embedding 服务 + 图片 OCR（纯本地 CPU，不需要任何 Key / 注册）

两个接口：
    POST /v1/embeddings   OpenAI 格式，给 ltc-rag 的向量检索用
    POST /v1/ocr          请求体是图片二进制，返回识别出的 Markdown（表格会还原）

用途：让检索从「纯 BM25」升级成「向量 + BM25 混合」，同时让前端可以直接拖图片进来入库。

为什么用 fastembed 而不是 sentence-transformers：
    fastembed 跑 ONNXRuntime，不拖 torch，常驻内存 200~300MB，3C4G 服务器也扛得住。
OCR 用 RapidOCR（同样 ONNX），模型在第一次调用时才加载，不用 OCR 就不占内存。

启动（scripts\\embed-up.ps1 会自动做这些）：
    python -m uvicorn embed_server:app --host 127.0.0.1 --port 8091

环境变量：
    EMBED_MODEL   模型名，默认 BAAI/bge-small-zh-v1.5（512 维，中文，约 95MB）
    EMBED_PREFIX  可选前缀，e5 系列模型需要 "query: " / "passage: "（本服务不分边，一般不用）
"""
import io
import os
import threading
import time
from typing import List, Union

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

from ltc_ocr import build_markdown, ocr_items
MODEL = os.getenv("EMBED_MODEL", "BAAI/bge-small-zh-v1.5")
PREFIX = os.getenv("EMBED_PREFIX", "")
# 单张图片上限，防止一个超大图把内存打爆
MAX_IMAGE_BYTES = 20 * 1024 * 1024
# 服务器上 Embedding 走硅基流动时，可设 OCR_ONLY=true，启动时不加载 embedding 模型（零内存闲置开销）
OCR_ONLY = os.getenv("OCR_ONLY", "false").lower() in ("1", "true", "yes")

_model = None
DIM = 0
_model_lock = threading.Lock()


def get_embed_model():
    global _model, DIM
    if _model is None:
        with _model_lock:
            if _model is None:
                from fastembed import TextEmbedding

                print(f"[embed] 加载模型 {MODEL} …", flush=True)
                started = time.time()
                m = TextEmbedding(model_name=MODEL)
                probe = next(iter(m.embed(["初始化"])))
                DIM = len(probe)
                _model = m
                print(f"[embed] 就绪：{MODEL}，{DIM} 维，耗时 {time.time() - started:.1f}s", flush=True)
    return _model


if not OCR_ONLY:
    get_embed_model()
else:
    print("[ocr] OCR_ONLY=true：跳过加载本地 Embedding 模型，仅按需提供 /v1/ocr 服务", flush=True)

app = FastAPI(title="LTC local embeddings & OCR")


class EmbeddingRequest(BaseModel):
    model: Union[str, None] = None
    input: Union[str, List[str]]


@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL, "dimension": DIM, "ocrReady": _ocr_engine is not None}


# RapidOCR 模型不小，懒加载：没人上传图片就完全不占内存
_ocr_engine = None
_ocr_lock = threading.Lock()


def get_ocr_engine():
    global _ocr_engine
    if _ocr_engine is None:
        with _ocr_lock:
            if _ocr_engine is None:
                from rapidocr import RapidOCR

                print("[ocr] 加载 RapidOCR 模型 …", flush=True)
                started = time.time()
                _ocr_engine = RapidOCR()
                print(f"[ocr] 就绪，耗时 {time.time() - started:.1f}s", flush=True)
    return _ocr_engine


@app.post("/v1/ocr")
async def ocr(request: Request):
    """
    请求体直接是图片二进制（Content-Type: image/*），返回识别出的 Markdown。

    不用 multipart 是为了少一个 python-multipart 依赖，Java 端发原始字节更省事。
    """
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="请求体为空，请直接发图片二进制")
    if len(body) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail=f"图片超过 {MAX_IMAGE_BYTES // 1024 // 1024}MB")

    try:
        from PIL import Image

        image = Image.open(io.BytesIO(body)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"不是可识别的图片：{e}")

    started = time.time()
    items = ocr_items(get_ocr_engine(), image)
    markdown = build_markdown(items, "auto")
    return {
        "markdown": markdown,
        "chars": len(markdown),
        "boxes": len(items),
        "width": image.width,
        "height": image.height,
        "elapsedMs": int((time.time() - started) * 1000),
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest):
    texts = [req.input] if isinstance(req.input, str) else list(req.input)
    if not texts:
        raise HTTPException(status_code=400, detail="input 不能为空")
    if PREFIX:
        texts = [PREFIX + t for t in texts]

    try:
        model = get_embed_model()
        vectors = [v.tolist() for v in model.embed(texts)]
    except Exception as e:  # 模型内部错误要带出去，不然客户端只看到 500
        raise HTTPException(status_code=500, detail=f"embedding 失败：{e}")

    data = [
        {"object": "embedding", "index": i, "embedding": vec}
        for i, vec in enumerate(vectors)
    ]
    # 只做粗略的 token 估算，调用方不用它计费，纯粹为了兼容 OpenAI 返回结构
    tokens = sum(len(t) for t in texts)
    return {
        "object": "list",
        "model": req.model or MODEL,
        "data": data,
        "usage": {"prompt_tokens": tokens, "total_tokens": tokens},
    }
