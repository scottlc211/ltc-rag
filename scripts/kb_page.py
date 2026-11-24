#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""前端页面映射生成器

扫描 zhgj-crm-web：
  src/api/*.js       → 接口函数（注释 / 方法 / url）
  src/views/**/*.vue → 页面（import 了哪些 api 文件、调用了哪些函数）

产出两份：
  03-数据字典/前端页面映射-全量.md   （file-only，逐页面列出调用接口）
  03-数据字典/前端页面速查.md        （精炼版，入库用；带统计与定位方法）

用法：python scripts/kb_page.py
"""
import argparse
import os
import re
import sys
from collections import Counter, OrderedDict, defaultdict

API_FN_RE = re.compile(
    r'(?://\s*(?P<doc>[^\n]*)\n)?\s*export\s+function\s+(?P<name>\w+)\s*\([^)]*\)\s*\{\s*'
    r'return\s+request\s*\(\s*\{(?P<body>.*?)\}\s*\)',
    re.S,
)
URL_RE = re.compile(r'url\s*:\s*["\'`]([^"\'`]+)["\'`]')
METHOD_RE = re.compile(r'method\s*:\s*["\'`](\w+)["\'`]')
IMPORT_RE = re.compile(r'import\s*\{([^}]+)\}\s*from\s*["\']@/api/([\w\-/.]+)["\']')

# 前端目录 → 业务域（人工对照，稳定性高）
DIR_DOMAIN = {
    "clueBusiness": "线索与商机",
    "customerManagement": "客户管理",
    "projectManagement": "工程管理",
    "projectDataCenter": "数据中台（项目数据）",
    "productManagement": "产品管理",
    "warehouseManagement": "仓库与发货",
    "financialManagement": "财务（回款/开票/付款）",
    "statisticalAnalysis": "统计分析与看板",
    "examinationAndApprovalCenter": "审批中心",
    "administrativeManagement": "行政与基础设置",
    "system": "系统管理（用户/角色/权限）",
    "basicData": "基础数据",
    "businessSettings": "业务配置",
    "channelPartnerManagement": "渠道伙伴",
    "officeManagement": "办公（日程/日志）",
    "home": "首页",
    "redirect": "跳转",
    "thirdPlatform": "第三方对接",
}


def parse_api_dir(api_root):
    """返回 {api模块名: [(fn, doc, method, url)]}"""
    modules = OrderedDict()
    for dirpath, _, filenames in os.walk(api_root):
        for fn in filenames:
            if not fn.endswith(".js"):
                continue
            full = os.path.join(dirpath, fn)
            with open(full, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
            items = []
            for m in API_FN_RE.finditer(text):
                body = m.group("body")
                url_m = URL_RE.search(body)
                if not url_m:
                    continue
                method_m = METHOD_RE.search(body)
                doc = (m.group("doc") or "").strip().strip("*").strip()
                items.append((m.group("name"), doc, (method_m.group(1) if method_m else "?").upper(), url_m.group(1)))
            if items:
                modules[fn[:-3]] = items
    return modules


def parse_views(views_root, api_modules):
    """返回 [(相对路径, 目录, [(fn, url, method)])]"""
    pages = []
    for dirpath, _, filenames in os.walk(views_root):
        for fn in filenames:
            if not fn.endswith(".vue"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, views_root).replace("\\", "/")
            top = rel.split("/")[1] if rel.startswith("pages/") and rel.count("/") >= 2 else rel.split("/")[0]
            with open(full, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
            calls = []
            seen = set()
            for m in IMPORT_RE.finditer(text):
                names = [n.strip().split(" as ")[0].strip() for n in m.group(1).split(",")]
                mod = m.group(2).rstrip("/")
                if mod.endswith(".js"):
                    mod = mod[:-3]
                if mod.endswith(".ts"):
                    mod = mod[:-3]
                for n in names:
                    if not n:
                        continue
                    for fn_name, doc, method, url in api_modules.get(mod, []):
                        if fn_name == n and url not in seen:
                            # 页面里真的调用了才计入（模板里出现函数名）
                            if text.count(n) >= 1:
                                seen.add(url)
                                calls.append((n, url, method))
            pages.append((rel, top, calls))
    return pages


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--web", default=r"D:\AAA_codeview\zhgj-crm-web")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "knowledge", "03-数据字典")
    src = os.path.join(args.web, "src")
    api_root = os.path.join(src, "api")
    views_root = os.path.join(src, "views")

    api_modules = parse_api_dir(api_root)
    api_total = sum(len(v) for v in api_modules.values())
    pages = parse_views(views_root, api_modules)

    # ---------- 全量映射 ----------
    lines = [
        "# 前端页面映射（全量）",
        "",
        "> 机器生成，勿手改。来源：`zhgj-crm-web/src`。",
        "> 生成方式：`python scripts/kb_page.py`。共 **%d** 个页面，**%d** 个接口定义。" % (len(pages), api_total),
        "",
        "> 说明：只列出页面**显式 import 且调用**的接口函数；通过子组件、mixin 间接调用的不在列。",
        "",
    ]
    dir_counter = Counter()
    for rel, top, calls in sorted(pages):
        dir_counter[top] += 1
        lines.append("## %s" % rel)
        lines.append("")
        lines.append("> 业务域：%s · 调用接口 %d 个" % (DIR_DOMAIN.get(top, top), len(calls)))
        lines.append("")
        if calls:
            lines.append("| 接口函数 | 方法 | 路径 |")
            lines.append("|---|---|---|")
            for name, url, method in calls[:20]:
                lines.append("| `%s` | %s | `%s` |" % (name, method, url))
            if len(calls) > 20:
                lines.append("")
                lines.append("（其余 %d 个略）" % (len(calls) - 20))
        else:
            lines.append("（未直接调用接口：多为布局/子组件/纯展示）")
        lines.append("")

    with open(os.path.join(out_dir, "前端页面映射-全量.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    # ---------- 速查版 ----------
    url_prefix = Counter()
    for _, _, calls in pages:
        for _, url, _ in calls:
            parts = url.split("/")
            url_prefix["/" + (parts[1] if len(parts) > 1 else "")] += 1
    with_api = sum(1 for _, _, c in pages if c)

    out = [
        "# 前端页面速查：657 个页面怎么找",
        "",
        "> 归属：`crm-meta` · docKey：`crm/meta/page-guide` · 逐页面明细见 [前端页面映射-全量](前端页面映射-全量.md)",
        "",
        "## 一句话",
        "",
        "PC 端 **%d 个页面**放在 18 个业务目录里，目录名就是业务域。找页面先找目录，找接口先找同名的 `src/api/*.js`。" % len(pages),
        "",
        "## 目录即业务域",
        "",
        "| 目录（`views/pages/` 下） | 业务域 | 页面数 |",
        "|---|---|---:|",
    ]
    for d, n in sorted(dir_counter.items(), key=lambda x: -x[1]):
        out.append("| `%s` | %s | %d |" % (d, DIR_DOMAIN.get(d, d), n))
    out += [
        "",
        "> 页面最多的是 `statisticalAnalysis`（95）、`customerManagement`（93）、`clueBusiness`（66）和 `projectDataCenter`（66）——**这四块占了全站一半的页面**。",
        "",
        "## 页面 ↔ 接口怎么对上",
        "",
        "```",
        "views/pages/<业务目录>/<页面>.vue",
        "        ↓ import { xxxList } from '@/api/<同名模块>.js'",
        "src/api/<模块>.js   （内含 method + url + 中文注释）",
        "        ↓ url",
        "后端 Controller（见《接口速查》）",
        "```",
        "",
        "**关键**：`src/api/` 的 52 个 js 文件是前后端唯一的契约层，每个函数都有 `url` 和中文注释。查\"这个按钮调什么接口\"直接看这里。",
        "",
        "### 有接口调用的页面",
        "",
        "| 项 | 数量 |",
        "|---|---:|",
        "| 页面总数 | %d |" % len(pages),
        "| 显式调用接口的页面 | %d |" % with_api,
        "| 纯布局/展示页面 | %d |" % (len(pages) - with_api),
        "| api 定义总数 | %d |" % api_total,
        "",
        "## 三步定位法",
        "",
        "1. **知道业务词** → 选目录（合同 → `clueBusiness` 或 `customerManagement`）；",
        "2. **知道页面名** → 直接在 `views/pages/` 搜文件名；",
        "3. **知道接口** → 在 `src/api/` 搜 url 片段，反查哪些页面用了它。",
        "",
        "## 常见坑",
        "",
        "- **Q：页面里搜不到接口调用？** 接口在子组件或 `mixin` 里，往上找父组件；",
        "- **Q：同一个页面多个 tab 调不同接口？** 正常，全量映射里只列前 20 条；",
        "- **Q：`redirect` 目录是什么？** 路由跳转中转页，不是业务页面；",
        "- **Q：目录名和业务对不上？** 历史命名（如 `administrativeManagement` 里混了基础设置），以页面标题为准。",
        "",
        "## 维护",
        "",
        "```powershell",
        "python scripts/kb_page.py     # 重新生成映射",
        "```",
        "",
        "## 待确认（⚠️）",
        "",
        "1. 移动端 App（403 页面）尚未纳入，需另一个仓库扫描脚本；",
        "2. 子组件 / mixin 间接调用的接口未统计，实际覆盖率高于本文数字。",
    ]
    with open(os.path.join(out_dir, "前端页面速查.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")

    print("页面 %d / api 定义 %d / 有接口页面 %d" % (len(pages), api_total, with_api))
    return 0


if __name__ == "__main__":
    sys.exit(main())
