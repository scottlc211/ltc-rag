#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
知识库基线扫描器：从 zhgj-crm-server 源码生成"全量清单"。

为什么要它：
  手写文档永远说不清"缺了什么"。只有先把系统里真实存在的东西
  （接口、表、定时任务、枚举、配置项）全部列出来编号，文档才有分母，
  才能算覆盖率、才能在生产上线前回答"哪块还没写"。

产出（knowledge/_inventory/）：
  01-api.md        模块 → 控制器 → 接口（方法名 + 路径 + HTTP 动词）
  02-tables.md     表 → 引用次数 → 出现的 Mapper（判断归属域的依据）
  03-jobs.md       定时任务类 → 方法 → 注释
  04-enums.md      枚举类 → 常量数 → 前若干常量
  05-config.md     业务配置项常量（CommonEnum 里的开关）

用法：
  python scripts/kb_inventory.py [--src D:\\AAA_codeview\\zhgj-crm-server]
可重复执行，每次覆盖输出，便于对比"这次比上次多了什么"。
"""
import argparse
import os
import re
import sys
from collections import Counter, defaultdict

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "knowledge", "03-数据字典")

JAVA_SUFFIX = ".java"
XML_SUFFIX = ".xml"

MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?["\']([^"\']*)["\']')
CLASS_MAPPING_RE = re.compile(
    r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?["\']([^"\']*)["\']')
METHOD_DEF_RE = re.compile(r'public\s+[\w<>\[\],\s\.\?]+\s+(\w+)\s*\(')
TABLE_RE = re.compile(r'(?i)\b(?:from|join|update|into)\s+`?(t_[a-z0-9_]+)`?')


def walk(root, suffix):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules", "logs")]
        for name in filenames:
            if name.endswith(suffix):
                yield os.path.join(dirpath, name)


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def module_of(path, src):
    """
    从路径里取 (入口层, 业务模块)。

    这个项目的控制器分布在四个根下，必须分开看，否则全被算成"management"：
      com/zhgj/app/<模块>        App（移动端）接口
      com/zhgj/management/<模块> Web 管理端接口
      com/zhgj/ltc/<模块>        LTC 业务接口
      com/zhgj/h5/<模块>         H5 页面接口
    """
    norm = path.replace("\\", "/")
    m = re.search(r"/com/zhgj/(app|management|ltc|h5)/([^/]+)/", norm)
    if m:
        return m.group(1), m.group(2)
    m = re.search(r"/com/zhgj/([^/]+)/", norm)
    if m:
        return "其他", m.group(1)
    rel = os.path.relpath(path, src).replace("\\", "/")
    return "其他", (rel.split("/")[0] if "/" in rel else rel)


def first_comment(path):
    """.java 文件顶部第一段 /** */ 或 // 注释的首行，用来给任务/枚举加说明。"""
    text = read(path)
    m = re.search(r"/\*\*(.*?)\*/", text, flags=re.S)
    if m:
        for line in m.group(1).splitlines():
            line = line.strip(" *\t")
            if line:
                return line[:60]
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("//"):
            body = line.lstrip("/").strip()
            if body:
                return body[:60]
    return ""


def scan_controllers(src):
    """返回 [(模块, 控制器, 类路径前缀, [(HTTP, 路径, 方法名)])]"""
    result = []
    for path in walk(src, JAVA_SUFFIX):
        text = read(path)
        if "@RestController" not in text and "@Controller" not in text:
            continue
        if "class " not in text:
            continue
        clean = strip_comments(text)
        class_prefix = ""
        # 类级 @RequestMapping 取 class 声明之前最后一个
        head = clean.split("class ", 1)[0]
        heads = CLASS_MAPPING_RE.findall(head)
        if heads:
            class_prefix = heads[-1]

        endpoints = []
        # 逐个方法扫描：拿到方法上方的映射注解 + 紧随其后的方法名
        for m in re.finditer(
                r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?["\']([^"\']*)["\']([^)]*)\)',
                clean):
            verb, sub, rest = m.group(1), m.group(2), m.group(3)
            tail = clean[m.end():m.end() + 400]
            md = METHOD_DEF_RE.search(tail)
            name = md.group(1) if md else "?"
            http = "ANY" if verb == "Request" else verb.upper()
            endpoints.append((http, sub, name))

        name = os.path.basename(path)[: -len(JAVA_SUFFIX)]
        layer, mod = module_of(path, src)
        result.append((layer, mod, name, class_prefix, endpoints, path))

    # 按 入口层 + 模块 + 控制器名 排序，输出稳定，便于 diff
    result.sort(key=lambda x: (x[0], x[1], x[2]))
    return result


def scan_tables(src):
    usage = Counter()
    owners = defaultdict(set)
    for path in walk(src, XML_SUFFIX):
        text = read(path)
        if not text:
            continue
        layer, pkg = module_of(path, src)
        for m in TABLE_RE.finditer(text):
            table = m.group(1).lower()
            usage[table] += 1
            owners[table].add(layer + "/" + pkg)
    return usage, owners


def scan_jobs(src):
    """定时任务：zhgj-scheduleTask 下类名含 Schedule/Task 的，抓 @Scheduled 与公开方法。"""
    jobs = []
    for path in walk(src, JAVA_SUFFIX):
        norm = path.replace("\\", "/")
        if "scheduleTask" not in norm:
            continue
        text = read(path)
        if "class " not in text:
            continue
        clean = strip_comments(text)
        cls = re.search(r"class\s+(\w+)", clean)
        if not cls:
            continue
        methods = []
        for m in re.finditer(r"public\s+[\w<>\[\],\s\.\?]+\s+(\w+)\s*\(", clean):
            name = m.group(1)
            if name in ("if", "for", "while", "switch"):
                continue
            methods.append(name)
        # 去重保序
        seen = set()
        uniq = [x for x in methods if not (x in seen or seen.add(x))]
        has_sched = "@Scheduled" in text or "@XxlJob" in text
        layer, mod = module_of(path, src)
        jobs.append((os.path.basename(path)[: -len(JAVA_SUFFIX)], mod,
                     has_sched, first_comment(path), uniq))
    jobs.sort(key=lambda x: (x[1], x[0]))
    return jobs


def scan_enums(src):
    enums = []
    for path in walk(src, JAVA_SUFFIX):
        if os.sep + "enums" + os.sep not in path and "/enums/" not in path.replace("\\", "/"):
            continue
        text = read(path)
        if not re.search(r"\benum\s+\w+", text):
            continue
        clean = strip_comments(text)
        cls = re.search(r"enum\s+(\w+)", clean)
        consts = re.findall(r"^\s*([A-Z][A-Z0-9_]{1,40})\s*\(", clean, flags=re.M)
        enums.append((os.path.basename(path)[: -len(JAVA_SUFFIX)], cls.group(1) if cls else "?",
                      first_comment(path), len(consts), consts[:8]))
    enums.sort(key=lambda x: (-x[3], x[0]))
    return enums


def scan_config(src):
    """CommonEnum 里的配置键：X("group", "key", "中文名")"""
    path = None
    for p in walk(src, JAVA_SUFFIX):
        if os.path.basename(p) == "CommonEnum.java":
            path = p
            break
    if not path:
        return []
    text = read(path)
    return [(g, k, cn) for g, k, cn in
            re.findall(r'\(\s*"([\w.]+)"\s*,\s*"([\w.]+)"\s*,\s*"([^"]+)"\s*\)', text)]


def write(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("写出 %s（%d 行）" % (path, len(lines)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default=r"D:\AAA_codeview\zhgj-crm-server")
    args = parser.parse_args()
    src = args.src
    if not os.path.isdir(src):
        print("源码目录不存在：%s" % src, file=sys.stderr)
        return 1

    # ---- 01 接口 ----
    controllers = scan_controllers(src)
    by_module = defaultdict(list)
    for item in controllers:
        by_module[(item[0], item[1])].append(item)

    lines = ["# 接口清单（机器生成，勿手改）", "",
             "> 生成方式：`python scripts/kb_inventory.py`",
             "> 用途：文档覆盖率的**分母**。每个模块的接口数就是该模块文档要交代的入口数。", "",
             "> 入口层：`app`=移动端，`management`=Web 管理端，`ltc`=LTC 业务，`h5`=H5 页面。", ""]
    total_ep = sum(len(c[4]) for c in controllers)
    lines.append("| 入口层 | 模块 | 控制器 | 接口数 |")
    lines.append("|---|---|---:|---:|")
    for key in sorted(by_module, key=lambda k: -sum(len(c[4]) for c in by_module[k])):
        ctrls = by_module[key]
        lines.append("| %s | **%s** | %d | %d |" % (key[0], key[1], len(ctrls),
                                                    sum(len(c[4]) for c in ctrls)))
    lines += ["", "**合计：%d 个控制器 / %d 个接口**" % (len(controllers), total_ep), ""]

    for key in sorted(by_module):
        lines.append("## %s / %s" % (key[0], key[1]))
        lines.append("")
        for _, _, cname, prefix, eps, _ in by_module[key]:
            lines.append("### %s  `%s`" % (cname, prefix or "/"))
            if not eps:
                lines.append("")
                lines.append("_（无方法级映射，可能继承父类）_")
                lines.append("")
                continue
            lines.append("")
            lines.append("| 接口 | 方法 | 路径 |")
            lines.append("|---|---|---|")
            for http, sub, name in eps:
                full = (prefix.rstrip("/") + "/" + sub.lstrip("/")).replace("//", "/")
                lines.append("| %s | `%s` | `%s` |" % (http, name, full))
            lines.append("")
    write(os.path.join(OUT_DIR, "接口清单.md"), lines)

    # 表相关产物已由 kb_schema.py 负责（字段字典 / 表结构索引 / 高频表速查），
    # 这里不再生成表清单，避免两份口径打架。
    usage, owners = scan_tables(src)  # 仅为下方汇总统计保留

    # ---- 03 定时任务 ----
    jobs = scan_jobs(src)
    lines = ["# 定时任务清单（机器生成）", "",
             "> 回收、提醒、报表推送都在这里。业务文档里说“定时任务做的”，必须能在这张表里找到对应类。", "",
             "| 类 | 模块 | @Scheduled | 说明 | 主要方法 |", "|---|---|---|---|---|"]
    for cls, mod, sched, comment, methods in jobs:
        shown = "、".join(["`%s`" % m for m in methods[:6]])
        if len(methods) > 6:
            shown += " 等 %d 个" % len(methods)
        lines.append("| %s | %s | %s | %s | %s |" % (cls, mod, "是" if sched else "否", comment, shown))
    lines += ["", "**合计：%d 个任务类**" % len(jobs), ""]
    write(os.path.join(OUT_DIR, "定时任务清单.md"), lines)

    # ---- 04 枚举 ----
    enums = scan_enums(src)
    lines = ["# 枚举字典清单（机器生成）", "",
             "> 状态码的权威出处。文档写到“状态 12”时，必须能在这里查到中文含义。", "",
             "| 文件 | 枚举 | 常量数 | 说明 | 前几个常量 |", "|---|---|---:|---|---|"]
    for fname, cls, comment, cnt, sample in enums:
        lines.append("| %s | %s | %d | %s | %s |" % (
            fname, cls, cnt, comment, "、".join(sample)))
    lines += ["", "**合计：%d 个枚举文件**" % len(enums), ""]
    write(os.path.join(OUT_DIR, "枚举字典.md"), lines)

    # ---- 05 配置项 ----
    cfg = scan_config(src)
    lines = ["# 业务配置项清单（机器生成）", "",
             "> 这些是“改规则不改代码”的开关，全部来自 CommonEnum。", "",
             "| 分组 | 配置键 | 中文名 |", "|---|---|---|"]
    for g, k, cn in cfg:
        lines.append("| %s | `%s` | %s |" % (g, k, cn))
    lines += ["", "**合计：%d 个配置项**" % len(cfg), ""]
    write(os.path.join(OUT_DIR, "业务配置项.md"), lines)

    print("\n汇总：控制器 %d，接口 %d，表 %d，定时任务 %d，枚举 %d，配置项 %d"
          % (len(controllers), total_ep, len(usage), len(jobs), len(enums), len(cfg)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
