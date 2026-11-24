#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""知识库推送器：把 knowledge/ 下的文档批量推送到 LTC RAG 服务

特点：
  * 幂等：服务端按 (namespace, docKey) 去重，内容没变会跳过重建，可以无脑重跑；
  * 有序：按 MANIFEST 里的批次顺序推送，台账类最后推（它们的数字反映最终状态）；
  * 增量：--only 只推指定 docKey，--changed 只推 git 有改动的文件；
  * 大文件默认不推（清单类全量文件会撑爆向量库），--include-large 才推。

用法：
  python scripts/kb_push.py --dry-run                     # 只看会推什么
  python scripts/kb_push.py --env local                   # 推到本地 8081
  python scripts/kb_push.py --env prod --base http://x:8081 --key <ADMIN_KEY>
  python scripts/kb_push.py --only crm/flow/contract      # 只推一篇
  python scripts/kb_push.py --ensure-namespace            # 先建命名空间再推
"""
import argparse
import glob
import json
import os
import sys
import urllib.error
import urllib.request

# ---------------------------------------------------------------- 清单
# (文件相对路径, docKey, 标题, 批次)
# 批次 1：底座（索引类，先让"有什么"可查）
# 批次 2：业务主干（流程）
# 批次 3：主数据 / 系统 / 看板 / 数据
# 批次 4：台账（最后推，数字才是最终状态）
MANIFEST = [
    # ---- 批次 1：使用说明与索引 ----
    ("00-使用说明/知识库说明.md", "meta/guide", "知识库使用说明", 1),
    ("00-使用说明/生产部署与更新.md", "meta/deploy", "生产部署与文档更新（怎么推到生产）", 1),
    ("03-数据字典/枚举字典.md", "crm/meta/enums", "状态码总表（全系统速查）", 1),
    ("03-数据字典/接口速查.md", "crm/meta/api-guide", "接口速查：1407 个接口怎么找", 1),
    ("03-数据字典/前端页面速查.md", "crm/meta/page-guide", "前端页面速查：661 个页面怎么找", 1),
    ("03-数据字典/字段速查-高频表.md", "crm/meta/fields", "字段速查：高频表的业务字段", 1),
    ("03-数据字典/表字典-业务分组.md", "crm/data/table-dictionary", "表字典：按业务域分组", 1),
    ("03-数据字典/高频表速查.md", "crm/meta/hot-tables", "高频表速查（字段数/模块/说明）", 1),
    ("03-数据字典/定时任务清单.md", "crm/meta/job-list", "定时任务清单（141 个）", 1),
    ("03-数据字典/业务配置项.md", "crm/meta/config-list", "业务配置项清单（133 个开关）", 1),
    ("02-代码地图/代码地图.md", "crm/code/code-map", "代码地图：从业务词找到代码与表", 1),

    # ---- 批次 2：业务流程（主干） ----
    ("01-业务流程/00-LTC总流程.md", "crm/flow/ltc-overview", "LTC 总业务流程（全景与泳道）", 2),
    ("01-业务流程/01-线索全生命周期.md", "crm/flow/lead-lifecycle", "线索全生命周期（线索池 → 分配 → 跟进 → 转化 / 回收）", 2),
    ("01-业务流程/02-线索转商机.md", "crm/flow/lead-to-opportunity", "商机：阶段推进、撞单判定与失单", 2),
    ("01-业务流程/03-报价流程.md", "crm/flow/quotation", "报价：底价校验、超额审批与转合同", 2),
    ("01-业务流程/04-合同流程.md", "crm/flow/contract", "合同全流程（销售/代理商/备货）", 2),
    ("01-业务流程/05-审批脊柱.md", "crm/flow/approval-spine", "审批脊柱：配置驱动的流程引擎", 2),
    ("01-业务流程/06-履约交付.md", "crm/flow/order-to-delivery", "履约交付：发货单→订单→子订单→安装", 2),
    ("01-业务流程/07-回款与开票.md", "crm/flow/collection-invoice", "回款与开票：核销关系、财务对账、红冲", 2),
    ("01-业务流程/08-台账与业绩.md", "crm/flow/ledger-performance", "台账与业绩：月度关账、业绩归属、激励核算", 2),
    ("01-业务流程/09-售后与退换货.md", "crm/flow/after-sales", "售后：维修派单与退换货", 2),

    # ---- 批次 3：主数据 / 系统 / 看板 / 数据 / 指标 ----
    ("06-主数据/01-客户主数据.md", "crm/data/customer", "客户主数据：企业库、业务公司、代理商、授信", 3),
    ("06-主数据/02-项目主数据.md", "crm/data/project", "项目主数据：工程阶段、评级、保护锁定", 3),
    ("06-主数据/03-产品主数据.md", "crm/data/product", "产品主数据：分类、底价、套餐、税率", 3),
    ("06-主数据/04-销售计划.md", "crm/data/sales-plan", "销售计划：年度→月度→部门→员工四级分解", 3),
    ("07-系统与权限/01-权限与数据范围.md", "crm/sys/permission", "权限与数据范围：谁能看到谁的数据", 3),
    ("07-系统与权限/02-消息通知与待办.md", "crm/sys/notice", "消息通知与待办：站内信、钉钉、短信", 3),
    ("07-系统与权限/03-外部集成.md", "crm/sys/integration", "外部集成：云客微信、实名制、开放接口", 3),
    ("08-看板与报表/01-看板与指标口径.md", "crm/data/dashboard", "看板与报表：指标口径与两个页面数不一样", 3),
    ("09-数据处理/01-数据中台与清洗.md", "crm/data/data-center", "数据中台：外部数据→清洗→推荐→线索", 3),
    ("04-指标口径/合同金额.md", "crm/metric/contract-amount", "指标：合同金额（签约额 / 订单总金额）", 3),
    ("04-指标口径/回款与回款率.md", "crm/metric/collection-and-rate", "指标：回款金额与回款率", 3),
    ("04-指标口径/线索商机转化率.md", "crm/metric/lead-opportunity-conversion", "指标：商机转化率（线索→商机→成交）", 3),

    # ---- 批次 4：台账（最后推） ----
    ("00-使用说明/文档覆盖检查表.md", "crm/meta/coverage", "文档覆盖检查表（缺什么一目了然）", 4),
    ("00-使用说明/待完成任务清单.md", "meta/todo", "待完成任务清单", 4),
]

# 体积大、只做本地参考的清单（默认不推）
LARGE_PATTERNS = [
    "03-数据字典/字段字典-全量.md",
    "03-数据字典/枚举字典-全量.md",
    "03-数据字典/接口清单.md",
    "03-数据字典/前端页面映射-全量.md",
    "03-数据字典/表结构索引-全量.md",
    "03-数据字典/字段字典-按域/*.md",
]


def http_json(url, method="POST", body=None, key=None, timeout=300):
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if key:
        headers["X-API-Key"] = key
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise RuntimeError("HTTP %s %s %s" % (e.code, url, detail))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8081", help="服务地址")
    ap.add_argument("--key", default=None, help="ADMIN API Key（也可用环境变量 LTC_API_KEY）")
    ap.add_argument("--namespace", default="ltc")
    ap.add_argument("--only", action="append", default=[], help="只推指定 docKey，可多次")
    ap.add_argument("--batch", type=int, default=0, help="只推某一批次")
    ap.add_argument("--include-large", action="store_true", help="同时推大体积清单文件")
    ap.add_argument("--ensure-namespace", action="store_true", help="先确保命名空间存在")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    key = args.key or os.environ.get("LTC_API_KEY")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    kb = os.path.join(root, "knowledge")

    targets = [(p, k, t, b) for p, k, t, b in MANIFEST
               if (not args.only or k in args.only) and (not args.batch or b == args.batch)]
    if args.include_large:
        for pat in LARGE_PATTERNS:
            for full in sorted(glob.glob(os.path.join(kb, pat))):
                rel = os.path.relpath(full, kb).replace("\\", "/")
                name = os.path.splitext(os.path.basename(rel))[0]
                doc_key = "crm/bulk/" + name.lower().replace("-", "-")
                if not args.only or doc_key in args.only:
                    targets.append((rel, doc_key, name, 9))

    missing = [t for t in targets if not os.path.exists(os.path.join(kb, t[0]))]
    if missing:
        for p, _, _, _ in missing:
            print("缺文件: %s" % p, file=sys.stderr)
        return 2

    print("待推送 %d 篇 → %s（namespace=%s）" % (len(targets), args.base, args.namespace))
    for p, k, t, b in targets:
        print("  [批次%d] %-42s %s" % (b, k, p))
    if args.dry_run:
        return 0

    if not key:
        print("缺少 --key 或环境变量 LTC_API_KEY", file=sys.stderr)
        return 2

    if args.ensure_namespace:
        try:
            http_json(args.base + "/v1/namespaces", "POST",
                      {"name": args.namespace, "description": "LTC CRM 知识库"}, key)
            print("命名空间已就绪：%s" % args.namespace)
        except RuntimeError as e:
            print("命名空间创建跳过（可能已存在）：%s" % e)

    ok = skipped = failed = 0
    for p, doc_key, title, batch in targets:
        path = os.path.join(kb, p)
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        try:
            res = http_json(args.base + "/v1/documents/text", "POST", {
                "content": content,
                "namespace": args.namespace,
                "docKey": doc_key,
                "title": title,
            }, key, timeout=600)
            doc = res.get("document") or {}
            changed = res.get("changed", True)
            if changed:
                ok += 1
                print("  ✅ %-42s v%s (%s 块)" % (doc_key, doc.get("version"), doc.get("chunkCount")))
            else:
                skipped += 1
                print("  ⏭  %-42s 未变化 (v%s)" % (doc_key, doc.get("version")))
        except RuntimeError as e:
            failed += 1
            print("  ❌ %-42s %s" % (doc_key, e))

    print("\n完成：更新 %d / 跳过 %d / 失败 %d" % (ok, skipped, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
