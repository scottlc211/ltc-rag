#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从 mysqldump/Navicat 导出的 DDL 生成字段级字典。

为什么单独一个脚本：
  kb_inventory.py 只能从 Mapper XML 看到"用了哪些表"，看不到字段。
  字段含义、类型、默认值、索引全在 DDL 里——这是"生产级文档"的底座。

产出（knowledge/_inventory/）：
  06-schema.md          每个表的完整字段（分组：按业务模块）
  06-schema-index.md    表 → 模块 → 字段数 → 表注释（速查，入库用这个）

用法：
  python scripts/kb_schema.py --ddl "C:\\Users\\Administrator\\Desktop\\zhgj_ltc.sql"
"""
import argparse
import os
import re
import sys
from collections import defaultdict

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "knowledge", "03-数据字典")

TABLE_HEAD_RE = re.compile(r"CREATE TABLE `([^`]+)` \(")
TABLE_TAIL_RE = re.compile(r"^\)\s*ENGINE.*?(?:COMMENT\s*=\s*'((?:[^'\\]|\\.)*)')?", re.S)
COLUMN_RE = re.compile(
    r"^\s*`([^`]+)`\s+([a-zA-Z]+(?:\([^)]*\))?(?:\s+unsigned)?(?:\s+zerofill)?)\s*(.*)$")
COMMENT_RE = re.compile(r"COMMENT\s+'((?:[^'\\]|\\.)*)'")
KEY_RE = re.compile(r"^\s*(PRIMARY KEY|UNIQUE KEY|KEY|INDEX)\s*(?:`([^`]+)`)?\s*\(([^)]*)\)")


def read(path):
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            with open(path, "r", encoding=enc) as f:
                return f.read()
        except UnicodeDecodeError:
            continue
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def parse_ddl(text):
    """返回 {表名: {"comment":..., "columns":[{name,type,null,default,comment}], "keys":[...]}}"""
    tables = {}
    pos = 0
    while True:
        m = TABLE_HEAD_RE.search(text, pos)
        if not m:
            break
        name = m.group(1)
        # 找到该表的结束：第一个以 ") ENGINE" 开头的行
        end = text.find("\n) ENGINE", m.end())
        if end < 0:
            break
        body = text[m.end():end]
        pos = end

        table_comment = ""
        tail = text[end:end + 600]
        cm = re.search(r"COMMENT\s*=\s*'((?:[^'\\]|\\.)*)'", tail)
        if cm:
            table_comment = cm.group(1).strip()

        columns, keys = [], []
        for raw in body.splitlines():
            line = raw.strip().rstrip(",")
            if not line:
                continue
            km = KEY_RE.match(raw)
            if km:
                kind, kn, cols = km.group(1), km.group(2) or "", km.group(3)
                cols = ", ".join(c.strip().strip("`") for c in cols.split(","))
                keys.append("%s %s(%s)" % (kind, (kn + " ") if kn else "", cols))
                continue
            cm2 = COLUMN_RE.match(raw)
            if not cm2:
                continue
            col, ctype, rest = cm2.group(1), cm2.group(2), cm2.group(3)
            ccm = COMMENT_RE.search(rest)
            default = ""
            dm = re.search(r"DEFAULT\s+('((?:[^'\\]|\\.)*)'|(\S+))", rest)
            if dm:
                default = dm.group(2) if dm.group(2) is not None else dm.group(3)
            columns.append({
                "name": col,
                "type": ctype.lower(),
                "null": "是" if "NOT NULL" not in rest else "否",
                "default": default,
                "comment": ccm.group(1).strip() if ccm else "",
            })
        tables[name] = {"comment": table_comment, "columns": columns, "keys": keys}
    return tables


def table_owners(src):
    """从 Mapper XML 反查表归属模块 + 引用次数（判断表的重要性）。"""
    owners = defaultdict(set)
    usage = defaultdict(int)
    pat = re.compile(r"(?i)\b(?:from|join|update|into)\s+`?([a-z0-9_]+)`?")
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules")]
        for fn in filenames:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                text = read(path)
            except OSError:
                continue
            norm = path.replace("\\", "/")
            m = re.search(r"/com/zhgj/(app|management|ltc|h5)/([^/]+)/", norm)
            pkg = ("%s/%s" % (m.group(1), m.group(2))) if m else "其他"
            for t in pat.findall(text):
                t = t.lower()
                owners[t].add(pkg)
                usage[t] += 1
    return owners, usage


def module_guess(table, owners):
    if table in owners:
        return "/".join(sorted(owners[table])[:1])
    prefix = table.split("_")[1] if table.startswith("t_") else table.split("_")[0]
    return "未在 Mapper 出现"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ddl", default=r"C:\Users\Administrator\Desktop\zhgj_ltc.sql")
    ap.add_argument("--src", default=r"D:\AAA_codeview\zhgj-crm-server")
    args = ap.parse_args()

    if not os.path.isfile(args.ddl):
        print("DDL 文件不存在：%s" % args.ddl, file=sys.stderr)
        return 1
    tables = parse_ddl(read(args.ddl))
    owners, usage = table_owners(args.src) if os.path.isdir(args.src) else ({}, {})

    # ---- 表结构索引-全量.md：速查 ----
    lines = ["# 表结构索引（机器生成）", "",
             "> 来源：DDL 导出 + Mapper XML 反查归属模块。",
             "> 完整字段见 `字段字典-全量.md`；本表用于快速定位：这张表属于哪个模块、有多少字段。", "",
             "| 表 | 模块 | 字段数 | 表说明 |", "|---|---|---:|---|"]
    groups = defaultdict(list)
    for name, info in sorted(tables.items()):
        groups[module_guess(name, owners)].append((name, info))
    for name, info in sorted(tables.items()):
        lines.append("| `%s` | %s | %d | %s |" % (
            name, module_guess(name, owners), len(info["columns"]),
            info["comment"].replace("|", "/")[:40]))
    lines += ["", "**合计：%d 张表**" % len(tables), ""]
    out1 = os.path.join(OUT_DIR, "表结构索引-全量.md")
    open(out1, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("写出 %s（%d 张表）" % (out1, len(tables)))

    # ---- 高频表速查.md：按代码引用次数排序，回答"这张表干嘛的、多少字段" ----
    hot = ["# 高频表速查（字段数 / 模块 / 说明）", "",
           "> 按 Mapper 引用次数排序的前 80 张表。查「这张表多少个字段、属于哪个模块」用这里。", "",
           "| 表 | 引用次数 | 字段数 | 模块 | 说明 |", "|---|---:|---:|---|---|"]
    for name, cnt in sorted(usage.items(), key=lambda kv: -kv[1])[:80]:
        info = tables.get(name)
        hot.append("| `%s` | %d | %d | %s | %s |" % (
            name, cnt, len(info["columns"]) if info else 0,
            module_guess(name, owners), (info["comment"] if info else "").replace("|", "/")[:40]))
    out3 = os.path.join(OUT_DIR, "高频表速查.md")
    open(out3, "w", encoding="utf-8", newline="\n").write("\n".join(hot) + "\n")
    print("写出 %s（前 80 张）" % out3)

    # ---- 06-schema.md：完整字段 ----
    lines = ["# 表结构字典（机器生成，勿手改）", "",
             "> 来源：`zhgj_ltc.sql`（DDL 导出）。字段含义取 DDL 的 COMMENT，未注释的留空。",
             "> 生成方式：`python scripts/kb_schema.py`", ""]
    for mod in sorted(groups):
        lines.append("## 模块 %s（%d 张表）" % (mod, len(groups[mod])))
        lines.append("")
        for name, info in groups[mod]:
            lines.append("### %s" % name)
            if info["comment"]:
                lines.append("")
                lines.append("> %s" % info["comment"])
            lines += ["", "| 字段 | 类型 | 可空 | 默认 | 含义 |", "|---|---|---|---|---|"]
            for col in info["columns"]:
                lines.append("| `%s` | %s | %s | %s | %s |" % (
                    col["name"], col["type"], col["null"],
                    ("`%s`" % col["default"]) if col["default"] else "",
                    col["comment"].replace("|", "/").replace("\n", " ")[:120]))
            if info["keys"]:
                lines += ["", "索引：`" + "`、`".join(info["keys"]) + "`"]
            lines.append("")
    out2 = os.path.join(OUT_DIR, "字段字典-全量.md")
    open(out2, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("写出 %s（%d 行）" % (out2, len(lines)))

    total_cols = sum(len(i["columns"]) for i in tables.values())
    no_comment = sum(1 for i in tables.values() for c in i["columns"] if not c["comment"])
    print("汇总：%d 张表 / %d 个字段 / 无注释字段 %d 个（%.1f%%）" % (
        len(tables), total_cols, no_comment, 100.0 * no_comment / max(1, total_cols)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
