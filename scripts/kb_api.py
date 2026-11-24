#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""接口清单生成器（逐条说明版）

扫描 zhgj-crm-server 所有 *Controller.java，抽取：
  HTTP 方法 / 完整路径 / Java 方法名 / 返回类型 / 入参 / 中文说明（取 Javadoc 首行）

输出 knowledge/03-数据字典/接口清单.md，按模块（com/zhgj/<mod>）分组。

用法：python scripts/kb_api.py [--src 源码根目录]
"""
import argparse
import os
import re
import sys
from collections import OrderedDict

OUT_NAME = "接口清单.md"

MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"[^)]*\))?'
)
BLOCK_COMMENT_RE = re.compile(r'/\*\*(.*?)\*/', re.S)
SIGN_RE = re.compile(
    r'\bpublic\s+([\w<>\[\],\s\.\?]+?)\s+(\w+)\s*\(([^)]*)\)'
)
PARAM_TYPE_RE = re.compile(r'(@\w+(?:\([^)]*\))?\s*)?([\w<>\[\],\.\?]+)\s+(\w+)')


def strip_comment(text):
    """取 Javadoc 里有意义的第一行"""
    text = text.replace("/**", "").replace("*/", "")
    for raw in text.splitlines():
        line = raw.strip().lstrip('*').strip()
        if not line or line.startswith('@'):
            continue
        return line.replace('|', '/')
    return ""


def parse_params(raw):
    """把参数串压成 'Type name' 列表，去掉注解噪音"""
    out = []
    depth = 0
    buf = ""
    for ch in raw:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(buf)
            buf = ""
        else:
            buf += ch
    if buf.strip():
        out.append(buf)
    result = []
    for p in out:
        p = p.strip()
        if not p:
            continue
        p = re.sub(r'@\w+(\([^)]*\))?\s*', '', p)   # 去注解
        p = re.sub(r'\bfinal\s+', '', p)
        parts = p.rsplit(' ', 1)
        if len(parts) == 2:
            result.append("%s %s" % (parts[0].strip(), parts[1].strip()))
    return result


def parse_controller(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    lines = text.splitlines()

    # 类级 @RequestMapping：取 class 声明之前最近的那个
    class_idx = next((i for i, l in enumerate(lines) if re.search(r'(public\s+)?class\s+\w+', l)), 0)
    base = ""
    for line in lines[:class_idx + 1]:
        m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', line)
        if m:
            base = m.group(1)

    endpoints = []
    pending_doc = ""
    i = 0
    while i < len(lines):
        line = lines[i]
        m = BLOCK_COMMENT_RE.search(text)  # 占位，真正解析按行下面的逻辑
        # 单行或多行 Javadoc
        if '/**' in line:
            block = []
            j = i
            while j < len(lines) and '*/' not in lines[j]:
                block.append(lines[j])
                j += 1
            if j < len(lines):
                block.append(lines[j])
            pending_doc = strip_comment("\n".join(block))
            i = j + 1
            continue

        # 类声明之前的是类级 @RequestMapping，不是接口
        mm = MAPPING_RE.search(line) if i > class_idx else None
        if mm and not line.strip().startswith('//'):
            http = mm.group(1).upper()
            if http == "REQUEST":
                http = "ANY"
            sub = mm.group(2) or ""
            # 往后找方法签名（最多 12 行）
            k = i + 1
            sig = None
            joined = ""
            while k < len(lines) and k < i + 20:
                joined += " " + lines[k].strip()
                sm = SIGN_RE.search(joined)
                if sm:
                    sig = sm
                    break
                k += 1
            if sig:
                ret = sig.group(1).strip()
                name = sig.group(2)
                params = parse_params(sig.group(3))
                endpoints.append({
                    "http": http,
                    "path": (base.rstrip('/') + '/' + sub.lstrip('/')).rstrip('/') or base,
                    "method": name,
                    "return": ret,
                    "params": params,
                    "doc": pending_doc,
                })
                pending_doc = ""
                i = k + 1
                continue
        i += 1
    return base, endpoints


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=r"D:\AAA_codeview\zhgj-crm-server")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "knowledge", "03-数据字典")

    modules = OrderedDict()
    total = 0
    for dirpath, dirnames, filenames in os.walk(args.src):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules")]
        for fn in filenames:
            if not fn.endswith("Controller.java"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, args.src).replace("\\", "/")
            # com/zhgj/<mod>/... → <mod>
            parts = rel.split("/")
            try:
                # 先定位 com/zhgj，跳过 zhgj-management 这种 maven 模块名
                idx = parts.index("com")
                if parts[idx + 1] != "zhgj":
                    raise ValueError
                tail = [p for p in parts[idx + 2:]
                        if p not in ("src", "main", "java", "controller", "controllers")]
                module = "/".join(tail[:2]) if len(tail) >= 2 else (tail[0] if tail else "other")
            except (ValueError, IndexError):
                module = "other"
            base, eps = parse_controller(full)
            if not eps:
                continue
            modules.setdefault(module, []).append((fn[:-5], base, eps, rel))
            total += len(eps)

    lines_out = [
        "# 接口清单（逐条说明）",
        "",
        "> 机器生成，勿手改。来源：`*Controller.java` 的注解 + Javadoc。",
        "> 生成方式：`python scripts/kb_api.py`。共 **%d** 个接口，**%d** 个控制器。" % (
            total, sum(len(v) for v in modules.values())),
        "",
        "## 怎么用",
        "",
        "1. 知道业务名 → 搜中文说明列；",
        "2. 知道路径片段 → 搜路径（如 `sales/plan`）；",
        "3. 想看某模块全部接口 → 跳对应模块小节；",
        "4. `ANY` 表示用 `@RequestMapping` 不限方法（GET/POST 都收）。",
        "",
        "## 模块总览",
        "",
        "| 模块 | 控制器 | 接口数 |",
        "|---|---:|---:|",
    ]
    for mod in sorted(modules):
        ctrls = modules[mod]
        lines_out.append("| `%s` | %d | %d |" % (mod, len(ctrls), sum(len(c[2]) for c in ctrls)))
    lines_out.append("")

    for mod in sorted(modules):
        ctrls = modules[mod]
        n = sum(len(c[2]) for c in ctrls)
        lines_out.append("## %s（%d 接口）" % (mod, n))
        lines_out.append("")
        for cname, base, eps, rel in sorted(ctrls, key=lambda x: x[0]):
            lines_out.append("### %s" % cname)
            lines_out.append("")
            lines_out.append("> 路径前缀：`%s` · 文件：`%s`" % (base or "(无)", rel.split("src/main/java/")[-1]))
            lines_out.append("")
            lines_out.append("| 方法 | 路径 | 说明 | 入参 | 返回 |")
            lines_out.append("|---|---|---|---|---|")
            for e in eps:
                params = ", ".join(e["params"]) if e["params"] else "—"
                doc = e["doc"] or e["method"]
                lines_out.append("| %s | `%s` | %s | `%s` | `%s` |" % (
                    e["http"], e["path"], doc, params, e["return"]))
            lines_out.append("")

    out = os.path.join(out_dir, OUT_NAME)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines_out) + "\n")
    print("写出 %s：%d 接口 / %d 模块" % (out, total, len(modules)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
