"""
OCR 公共逻辑：图片 → Markdown（含表格还原）。

被两个地方共用：
    img2md.py      命令行：pdf / docx / 图片 → .md
    embed_server.py  服务端：POST /v1/ocr 给 Java 的图片解析器调用

核心不是"ocr 出一堆文字"，而是把识别框按坐标聚成行列，还原成 Markdown 表格；
顺带纠正 OCR 把下划线认成空格的通病。
"""
from __future__ import annotations

from typing import Any


def cluster(values: list[float], tol: float) -> list[float]:
    """一维聚类：把相近的坐标合成一组，返回每组的中心值（保持从小到大）。"""
    if not values:
        return []
    values = sorted(values)
    groups: list[list[float]] = [[values[0]]]
    for v in values[1:]:
        if v - groups[-1][-1] <= tol:
            groups[-1].append(v)
        else:
            groups.append([v])
    return [sum(g) / len(g) for g in groups]


def ocr_items(engine: Any, image: Any) -> list[dict]:
    """跑一次 OCR，返回 [{x, y, w, h, cx, cy, text, score}]，按 y 再按 x 排序。"""
    result = engine(image)
    items: list[dict] = []
    # boxes 是 numpy 数组，不能直接 if not result.boxes（会报"多元素真值不明确"）
    if result is None or result.boxes is None or len(result.boxes) == 0:
        return items
    for box, text, score in zip(result.boxes, result.txts, result.scores):
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        items.append({
            "x": min(xs), "y": min(ys),
            "w": max(xs) - min(xs), "h": max(ys) - min(ys),
            "cx": (min(xs) + max(xs)) / 2,
            "cy": (min(ys) + max(ys)) / 2,
            "text": text.strip(), "score": float(score),
        })
    items.sort(key=lambda it: (it["y"], it["x"]))
    return items


def normalize_identifier_columns(grid: list[list[str]]) -> None:
    """
    纠正"下划线被识别成空格"。

    OCR 对下划线这种细笔画很不友好，表名常被读成 "t clue_business"。
    判定方法：某一列数据行全是 ASCII、且至少有一个格子带下划线 —— 说明这列是标识符，
    那么该列里带空格的格子，空格基本就是下划线。中文列不受影响。
    """
    if not grid:
        return
    for col in range(len(grid[0])):
        # 判定只看数据行：表头是中文（"英文表名"），带上它整列就被判成非 ASCII 了
        body_values = [row[col] for row in grid[1:] if row[col]]
        if not body_values:
            continue
        ascii_only = all(all(ord(ch) < 128 for ch in v) for v in body_values)
        if not ascii_only or not any("_" in v for v in body_values):
            continue
        for row in grid:
            if row[col] and " " in row[col]:
                row[col] = row[col].replace(" ", "_")


def build_markdown(items: list[dict], mode: str = "auto") -> str:
    """把文本框按行/列聚合成 Markdown。mode: auto | table | lines"""
    if not items:
        return ""

    heights = [it["h"] for it in items if it["h"] > 0]
    line_h = sorted(heights)[len(heights) // 2] if heights else 20
    row_tol = max(line_h * 0.6, 6)

    row_centers = cluster([it["cy"] for it in items], row_tol)
    rows: list[list[dict]] = [[] for _ in row_centers]
    for it in items:
        idx = min(range(len(row_centers)), key=lambda i: abs(row_centers[i] - it["cy"]))
        rows[idx].append(it)

    col_centers = cluster([it["x"] for it in items], max(line_h * 2.5, 30))
    use_table = mode == "table" or (mode == "auto" and len(col_centers) >= 2)

    if not use_table:
        lines: list[str] = []
        for row in rows:
            texts = [it["text"] for it in sorted(row, key=lambda it: it["x"])]
            if texts:
                lines.append(" ".join(texts))
        return "\n".join(lines)

    grid: list[list[str]] = []
    for row in rows:
        cells: list[list[str]] = [[] for _ in col_centers]
        for it in sorted(row, key=lambda it: it["x"]):
            idx = min(range(len(col_centers)), key=lambda i: abs(col_centers[i] - it["x"]))
            cells[idx].append(it["text"])
        grid.append([" ".join(c).replace("|", "\\|") for c in cells])

    if not grid:
        return ""
    normalize_identifier_columns(grid)

    header, body = grid[0], grid[1:]
    out = ["| " + " | ".join(header) + " |",
           "| " + " | ".join("---" for _ in header) + " |"]
    for row in body:
        out.append("| " + " | ".join(row) + " |")
    return "\n".join(out)
