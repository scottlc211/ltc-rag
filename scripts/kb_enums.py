#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""枚举字典生成器

从 zhgj-crm-server 的 DictEnum / CommonEnum / ClueBusinessEnum 里扫出所有
"数值 key + 中文含义" 的枚举，生成可检索的《枚举字典.md》。

用法：python scripts/kb_enums.py [--src 源码根目录]
"""
import argparse
import os
import re
import sys

OUT_NAME = "枚举字典-全量.md"

# 只收有业务含义的枚举：排除文件类型、性别这类与状态码无关的
SKIP = {
    "FileCategory", "ClueFile", "PaymentFile", "ProcessFile", "GoodsFile",
    "CustomerCompanyFileEnum", "ChannelPartnerFileEnum", "CreditLimitFileEnum",
    "Gender", "TaxRate", "ProductUnit", "UnitType", "PostType",
}

CONST_RE = re.compile(r'^\s*([A-Z][A-Z0-9_]*)\s*\(\s*(-?\d+)\s*,\s*"([^"]*)"')
ENUM_RE = re.compile(r'^\s*(?:public\s+)?enum\s+(\w+)')


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def parse_enum(lines, start, name):
    """从 enum 声明行往下扫常量，遇到枚举闭合或下一个 enum 就停。

    返回 [(常量, 值, 含义)]。
    """
    items = []
    seen = set()
    for line in lines[start + 1:]:
        stripped = line.strip()
        # 枚举体结束：单独一行的 } 或 };
        if stripped in ("}", "};"):
            break
        # 保险：碰到下一个 enum 声明也停
        if ENUM_RE.match(line):
            break
        m = CONST_RE.match(line)
        if m:
            key = m.group(1)
            if key in seen:      # 同一枚举内重复常量名，说明越界了
                break
            seen.add(key)
            items.append((key, int(m.group(2)), m.group(3)))
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=r"D:\AAA_codeview\zhgj-crm-server")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "knowledge", "03-数据字典")
    targets = []
    for dirpath, dirnames, filenames in os.walk(args.src):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules")]
        for fn in ("DictEnum.java", "CommonEnum.java", "ClueBusinessEnum.java"):
            if fn in filenames:
                targets.append(os.path.join(dirpath, fn))
    if not targets:
        print("没找到枚举源码文件", file=sys.stderr)
        return 1

    found = {}   # name -> (file, line, items)
    for path in targets:
        lines = read(path)
        fname = os.path.basename(path)
        for i, line in enumerate(lines):
            m = ENUM_RE.match(line)
            if not m:
                continue
            name = m.group(1)
            if name in SKIP:
                continue
            items = parse_enum(lines, i, name)
            if len(items) < 2:
                continue
            found[name] = (fname, i + 1, items)

    lines_out = [
        "# 枚举字典（状态码速查）",
        "",
        "> 机器生成，勿手改。来源：`DictEnum.java` / `CommonEnum.java` / `ClueBusinessEnum.java`。",
        "> 生成方式：`python scripts/kb_enums.py`。共 %d 个业务枚举。" % len(found),
        "",
        "## 怎么用",
        "",
        "1. 知道业务名 → Ctrl+F 搜中文（如「待履约」）或搜枚举名（如 `ClueContractStatus`）；",
        "2. 知道数字但不知道含义 → 搜 `= 11` 这种形式不容易命中，建议先按业务域定位枚举名再查表；",
        "3. 代码里的判断用**数值**，文案可能有细微差异，以数值为准。",
        "",
        "## 高频枚举速查（先看这几个）",
        "",
        "| 业务 | 枚举 | 取值 |",
        "|---|---|---|",
    ]
    hot = ["ClueStatus", "ClueBusinessStatus", "ClueContractStatus", "QuotationStage",
           "GoodsDeliverStatus", "GoodSOrderStatus", "GoodSPurchaseStatus",
           "PaymentStatus", "PaymentCollectionStatus", "ReceiptStatus",
           "ProtectionLockEnum", "ClueRepeatType", "AgreementStatus"]
    for name in hot:
        if name in found:
            items = found[name][2]
            brief = " / ".join("%d=%s" % (v, t) for _, v, t in items[:12])
            lines_out.append("| %s | `%s` | %s |" % ("", name, brief))
    lines_out.append("")

    for name in sorted(found):
        fname, lineno, items = found[name]
        lines_out.append("## %s" % name)
        lines_out.append("")
        lines_out.append("> 定义：`%s:%d`" % (fname, lineno))
        lines_out.append("")
        lines_out.append("| 值 | 常量 | 含义 |")
        lines_out.append("|---:|---|---|")
        for const, val, text in items:
            lines_out.append("| %d | `%s` | %s |" % (val, const, text.replace("|", "/")))
        lines_out.append("")

    out = os.path.join(out_dir, OUT_NAME)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines_out) + "\n")
    print("写出 %s（%d 个枚举）" % (out, len(found)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
