"""
图片 / 扫描件 / 截图 → Markdown（RapidOCR + 行列聚类）

为什么单独写这个：
    Docling 擅长"有版面结构的文档"，但遇到"整页就是一张图片"（比如 Word 里贴了一张表格截图），
    它会把图当图片元素导出成 base64，根本不 OCR。
    这里直接用 RapidOCR 识别整页，再按坐标把文本框聚成行列，还原成 Markdown 表格。

用法：
    python img2md.py 输入.pdf|docx|png [-o 输出.md] [--dpi 300] [--mode auto|table|lines]

    --mode table  强制输出 Markdown 表格
    --mode lines  只按行输出纯文本
    --mode auto   自动判断（能识别出 >=2 列就出表格）

表格还原逻辑在 ltc_ocr.py 里，和服务端 /v1/ocr 共用同一份实现。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from ltc_ocr import build_markdown, ocr_items
from rapidocr import RapidOCR

IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tif", ".tiff"}
# docx 里图片固定放在 word/media/ 下，按文件名排序就是文档里的顺序
DOCX_MEDIA_PREFIX = "word/media/"


def load_pages(path: Path, dpi: int):
    """返回 [(序号, PIL.Image)]。PDF 逐页渲染，图片直接读，docx 抠出内嵌图片。"""
    suffix = path.suffix.lower()
    if suffix in IMAGE_SUFFIXES:
        from PIL import Image

        yield 1, Image.open(path).convert("RGB")
        return

    if suffix == ".docx":
        # docx 本质是 zip，正文里的图片都在 word/media 下，不需要先转 PDF
        import io
        import zipfile

        from PIL import Image

        with zipfile.ZipFile(path) as zf:
            media = sorted(
                (n for n in zf.namelist() if n.startswith(DOCX_MEDIA_PREFIX)),
                key=lambda n: n.lower(),
            )
            if not media:
                raise SystemExit("这个 docx 里没有内嵌图片")
            for index, name in enumerate(media, start=1):
                try:
                    yield index, Image.open(io.BytesIO(zf.read(name))).convert("RGB")
                except Exception as e:  # 矢量图（emf/wmf）PIL 打不开，跳过并提示
                    print(f"[warn] 跳过 {name}：{e}", file=sys.stderr)
        return

    import pypdfium2 as pdfium

    pdf = pdfium.PdfDocument(str(path))
    for index in range(len(pdf)):
        bitmap = pdf[index].render(scale=dpi / 72)
        yield index + 1, bitmap.to_pil()


def main() -> int:
    parser = argparse.ArgumentParser(description="图片/扫描件转 Markdown")
    parser.add_argument("input", help="PDF / docx / 图片路径")
    parser.add_argument("-o", "--output", help="输出 .md 路径（默认与输入同目录同名的 .md）")
    parser.add_argument("--dpi", type=int, default=300, help="PDF 渲染精度，默认 300")
    parser.add_argument("--mode", choices=["auto", "table", "lines"], default="auto")
    args = parser.parse_args()

    src = Path(args.input)
    if not src.exists():
        print(f"文件不存在：{src}", file=sys.stderr)
        return 2
    dst = Path(args.output) if args.output else src.with_suffix(".md")

    engine = RapidOCR()
    unit = "图片" if src.suffix.lower() == ".docx" else "页"
    blocks: list[str] = []
    for page_no, image in load_pages(src, args.dpi):
        body = build_markdown(ocr_items(engine, image), args.mode)
        if not body.strip():
            print(f"[warn] 第 {page_no} {unit}没识别出文字", file=sys.stderr)
            continue
        blocks.append(f"## 第 {page_no} {unit}\n\n{body}")

    text = "\n\n".join(blocks).strip()
    dst.write_text(text + "\n", encoding="utf-8")
    print(f"识别完成：{dst}（{len(text)} 字符）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
