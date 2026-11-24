#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""字段字典分域生成器

把《字段字典-全量.md》（375 张表）按 9 个业务域拆开，另生成一份
入库用的《字段速查-高频表.md》（只收业务字段，跳过审计字段）。

产出：
  knowledge/03-数据字典/字段字典-按域/01-线索与商机.md ... 09-数据中台.md
  knowledge/03-数据字典/字段速查-高频表.md

用法：python scripts/kb_fields.py
"""
import os
import re
import sys
from collections import OrderedDict

TABLE_RE = re.compile(r'^### (t_\w+|c_\w+|yk_\w+)$')
ROW_RE = re.compile(r'^\| `(\w+)` \| ([\w\(\), ]+) \| (\S+) \| (\S*) \| (.*?) \|$')

# 9 个业务域：按表名前缀/关键词归类，顺序即优先级
DOMAINS = OrderedDict([
    ("01-线索与商机", {
        "kw": ["t_clue", "t_project_contact", "t_project_customer", "t_project_relation",
               "t_project_stage", "t_business_settings", "t_business_repeat"],
        "desc": "线索池、分配、跟进、商机、撞单",
    }),
    ("02-报价与合同", {
        "kw": ["t_clue_quotation", "t_quotation", "t_agreement", "t_clue_contract", "t_contract"],
        "desc": "报价单、合同、协议、条款",
    }),
    ("03-履约与仓库", {
        "kw": ["t_goods", "t_clue_deliver", "t_deliver", "t_order", "t_inventory", "t_purchase",
               "t_supplier", "t_install", "t_maintenance", "t_return", "t_warehouse", "t_stock"],
        "desc": "发货、订单、子订单、库存、采购、安装、维修、退换货",
    }),
    ("04-回款与财务", {
        "kw": ["t_payment", "t_receipt", "t_invoice", "t_credit", "t_finance", "t_bill"],
        "desc": "回款、核销、开票、红冲、授信、付款",
    }),
    ("05-客户与工程", {
        "kw": ["t_customer", "t_company", "t_project", "t_agent", "t_agency", "t_channel",
               "t_supplier_info", "t_corp"],
        "desc": "企业库、业务公司、代理商、渠道、工程项目",
    }),
    ("06-产品与协议", {
        "kw": ["t_product", "t_category", "t_brand", "t_copyright", "t_tax", "t_package"],
        "desc": "产品、类目、品牌、软著、税率、产品包",
    }),
    ("07-审批与流程", {
        "kw": ["t_process", "t_notice"],
        "desc": "审批配置、实例、进度、抄送、消息通知",
    }),
    ("08-系统与权限", {
        "kw": ["t_user", "t_role", "t_menu", "t_department", "t_setting", "t_config", "t_log",
               "t_system", "t_daily", "t_file", "t_dict"],
        "desc": "用户、角色、部门、区域、配置、日志、字典",
    }),
    ("09-数据中台", {
        "kw": ["c_", "t_data_", "yk_", "t_recommend"],
        "desc": "清洗表、业务数据、推荐、云客微信",
    }),
])

OTHER = ("10-其他", "未归入以上九域的表")

# 入库版要跳过的审计/无关字段
SKIP_FIELDS = {"IS_DELETE", "CREATE_USER_ID", "UPDATE_USER_ID", "CREATE_TIME", "UPDATE_TIME",
               "CREATE_DEPARTMENT_ID", "UPDATE_DEPARTMENT_ID", "VERSION", "SORT"}

# 入库版优先收录的高频表（业务最常问的）
HOT_TABLES = [
    "t_clue", "t_clue_business", "t_clue_contact", "t_clue_quotation", "t_clue_quotation_detail",
    "t_clue_business_repeat", "t_clue_contract", "t_clue_contract_bill", "t_clue_contract_product",
    "t_agreement", "t_goods_deliver", "t_goods_deliver_detail", "t_goods_order", "t_goods_sub_order",
    "t_goods_sub_order_detail", "t_goods_inventory", "t_goods_purchase",
    "t_payment", "t_payment_collection", "t_payment_collection_contract", "t_payment_receipt",
    "t_payment_receipt_write_off", "t_credit_limit",
    "t_customer_info", "t_company_info", "t_customer_agent", "t_project_info",
    "t_product_info", "t_product_guide_price", "t_product_tax_rate",
    "t_process_conf", "t_process_result", "t_process_progress", "t_process_level",
    "t_sales_plan", "t_sales_dept_plan", "t_sales_employee_plan",
    "t_sales_performance_info", "t_sales_performance_record", "t_user", "t_notice",
]


def read_blocks(path):
    """解析字段字典，返回 OrderedDict{table: (desc, [rows])}"""
    tables = OrderedDict()
    cur = None
    desc = ""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            m = TABLE_RE.match(line)
            if m:
                cur = m.group(1)
                desc = ""
                tables[cur] = (desc, [])
                continue
            if cur is None:
                continue
            if line.startswith("> ") and not desc:
                desc = line[2:].strip()
                tables[cur] = (desc, tables[cur][1])
                continue
            r = ROW_RE.match(line)
            if r:
                tables[cur][1].append((r.group(1), r.group(2), r.group(3), r.group(4), r.group(5).strip()))
    return tables


def classify(table):
    for name, cfg in DOMAINS.items():
        for kw in cfg["kw"]:
            if table.startswith(kw):
                return name
    return OTHER[0]


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    base = os.path.join(root, "knowledge", "03-数据字典")
    src = os.path.join(base, "字段字典-全量.md")
    if not os.path.exists(src):
        print("缺少 %s，先跑 kb_schema.py" % src, file=sys.stderr)
        return 1

    tables = read_blocks(src)
    buckets = OrderedDict((name, []) for name in list(DOMAINS) + [OTHER[0]])
    for t in tables:
        buckets[classify(t)].append(t)

    out_dir = os.path.join(base, "字段字典-按域")
    os.makedirs(out_dir, exist_ok=True)

    summary = []
    for name, tbs in buckets.items():
        desc = DOMAINS[name]["desc"] if name in DOMAINS else OTHER[1]
        lines = [
            "# 字段字典 · %s" % name.replace("-", " · ", 1),
            "",
            "> 覆盖：%s" % desc,
            "> 本域共 **%d** 张表。全量见 `../字段字典-全量.md`，机器生成勿手改（`python scripts/kb_fields.py`）。" % len(tbs),
            "",
            "## 表清单",
            "",
            "| 表 | 说明 | 字段数 |",
            "|---|---|---:|",
        ]
        for t in sorted(tbs):
            d, rows = tables[t]
            lines.append("| `%s` | %s | %d |" % (t, d or "-", len(rows)))
        lines.append("")
        for t in sorted(tbs):
            d, rows = tables[t]
            lines.append("## %s" % t)
            lines.append("")
            if d:
                lines.append("> %s" % d)
                lines.append("")
            lines.append("| 字段 | 类型 | 可空 | 默认 | 含义 |")
            lines.append("|---|---|---|---|---|")
            for f, ty, nu, df, cm in rows:
                lines.append("| `%s` | %s | %s | %s | %s |" % (f, ty, nu, df or "—", cm))
            lines.append("")
        with open(os.path.join(out_dir, name + ".md"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        summary.append((name, len(tbs), desc))

    # ---------- 入库版：高频表业务字段 ----------
    out = [
        "# 字段速查：高频表的业务字段",
        "",
        "> 归属：`crm-meta` · docKey：`crm/meta/fields` · 全量 375 张表见 `字段字典-全量.md`，按域见 `字段字典-按域/`",
        "",
        "## 一句话",
        "",
        "查字段先查这里（40 张高频表的业务字段），查不到再去全量。**审计字段**（`IS_DELETE`/`CREATE_TIME`/`CREATE_USER_ID`）每张表都有，这里统一省略。",
        "",
        "## 表的分布（按业务域）",
        "",
        "| 业务域 | 表数 | 覆盖内容 |",
        "|---|---:|---|",
    ]
    for name, n, desc in summary:
        out.append("| %s | %d | %s |" % (name, n, desc))
    out += ["", "## 高频表字段", ""]

    hit = 0
    for t in HOT_TABLES:
        if t not in tables:
            continue
        d, rows = tables[t]
        biz = [r for r in rows if r[0] not in SKIP_FIELDS]
        if not biz:
            continue
        hit += 1
        out.append("### %s" % t)
        out.append("")
        if d:
            out.append("> %s（共 %d 字段，列出 %d 个业务字段）" % (d, len(rows), len(biz)))
            out.append("")
        out.append("| 字段 | 类型 | 含义 |")
        out.append("|---|---|---|")
        for f, ty, nu, df, cm in biz:
            out.append("| `%s` | %s | %s |" % (f, ty, cm or "—"))
        out.append("")

    out += [
        "## 怎么查",
        "",
        "1. 表名已知 → 搜 `### t_表名`；",
        "2. 只知道业务词 → 搜中文含义列；",
        "3. 表不在这里 → 去 `字段字典-按域/` 找对应域，或 `字段字典-全量.md`；",
        "4. 想知道某表被谁用（哪个模块）→ 看《表结构索引》的模块列。",
        "",
        "## 待确认（⚠️）",
        "",
        "1. 全量 375 张表、7014 个字段，本文件只覆盖 %d 张高频表的业务字段，其余按需查全量。" % hit,
        "2. 部分字段含义在 DDL 注释里为空，需业务确认。",
    ]
    with open(os.path.join(base, "字段速查-高频表.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")

    print("分域写出 %d 个文件；高频表 %d 张；字段速查 %d 行" % (
        len(summary), hit, len(out)))
    for name, n, _ in summary:
        print("  %-16s %3d 表" % (name, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
