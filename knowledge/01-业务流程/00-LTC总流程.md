# LTC 总业务流程（Lead To Cash 全景与泳道）

> 归属：`crm-flow` · docKey：`crm/flow/ltc-overview` · 证据：`LTC总体业务流程.png` + zhgj-crm-server 代码

## 一句话

LTC = 从"一条线索"走到"一笔回款"。全公司只有一条主干：**线索 → 商机 → 报价 → 合同 → 订单 → 发货 → 交付安装 → 回款/开票 → 台账业绩**。每一段都有明确的"谁做、在哪个页面做、落到哪张表"。

## 主干全景

```
[基础数据]  企业库/工程库/产品库  ──►  [线索池] 采集·清洗·入库
                                            │ 平均分配(区域+工程质量)
                                            ▼
[线索管理] 我的线索 ──跟进──► 转化(无撞单+至少一次有效跟进) ──► [商机管理] 销售商机
   │ 转化失败/失单                                                    │ 阶段:初步意向→需求沟通→方案→报价→合同
   └────────────► 回收线索 → 线索池 ◄──────────────────────────────┘ 失单回收
                                                                      ▼
[报价管理] 销售报价(撞单判定) → 草稿 → 赢单成交 ──► [合同管理] 创建合同 → 审批
                                                                      ▼
[交付管理] 发货申请(可多批次) → 客户收货签字 → 订单管理 → 硬件发货(仓库/供应商代发) → 安装派单
                                                                      ▼
[财务管理] 开票申请 → 开票 → 回款核销 → 应收/应付
                                                                      ▼
[数据管理] 合同台账 → 业绩/回款/达成率
```

## 九个阶段 × 角色 × 代码 × 表

### 1. 基础数据（企业库 / 工程库 / 产品库）
| 项 | 内容 |
|---|---|
| 谁维护 | 数据管理员 |
| 入口 | 客户、工程、产品主数据维护 |
| 代码 | `customer` / `project` / `product` 包 |
| 表 | `t_customer_info`、`t_company_info`、`t_project_info`、`t_product_info` |
| 关键状态 | 工程库的【工程评级】与【保护锁定状态：已锁定 / 保护期内 / 无】——这两个字段直接决定后面能不能转化 |

> 保护锁定状态是被后续所有环节引用的全局约束：撞单判定、商机转化、合同审批都会读它。

### 2. 线索池
| 项 | 内容 |
|---|---|
| 触发 | 数据采集（工程款系统等外部渠道）→ 数据清洗 → 数据入库；或人为新增 |
| 分配规则 | **平均分配（区域 + 工程质量）**，由 `AllocateClueRuleArea` 执行 |
| 代码 | `ClueController` → `ClueServiceImpl` → `ClueRecyclingScheduleServiceImpl`（定时分配/回收） |
| 表 | `t_clue`、`t_clue_allocate_record` |
| 配置 | `t_business_settings` / `t_system_setting`（保护期、提醒天数、分配权重） |

### 3. 线索管理
| 项 | 内容 |
|---|---|
| 动作 | 领取、分配、转移、跟进、编辑 |
| 出口 1 | **转化成功** → 商机 |
| 出口 2 | **转化失败 / 失单** → 回收线索 → 回线索池 |
| 代码 | `ClueServiceImpl`（add/allocate/transfer/drawDown/transform/collect） |
| 表 | `t_clue`、`t_clue_follow_record`、`t_clue_fail_record` |

### 4. 商机管理
| 项 | 内容 |
|---|---|
| 转化前置条件 | **① 无撞单；② 至少一次有效跟进**（两个条件同时满足才允许转化） |
| 阶段 | 初步意向 → 需求沟通 → 方案 → 报价 → 合同 |
| 撞单判定 | 转化和报价时都要过【撞单判定】，依据工程的**保护期 / 锁定期** |
| 代码 | `BusinessController` → `BusinessServiceImpl`；撞单 `BusinessRepeatServiceImpl` |
| 表 | `t_clue_business`、`t_business_settings` |
| 出口 | 失单 → 回收线索；成功 → 报价 |

### 5. 报价管理
| 项 | 内容 |
|---|---|
| 动作 | 销售报价（含撞单判定）→ 填写信息 → 草稿保存 → 赢单成交 |
| 代码 | `ClueQuotationController` → `ClueQuotationServiceImpl` |
| 表 | `t_clue_quotation`、`t_clue_quotation_detail` |
| 出口 | 赢单成交 → 创建合同 |

### 6. 合同管理
| 项 | 内容 |
|---|---|
| 动作 | 创建合同 → 提交审批 → 审批通过 → 自动生成订单 |
| 业务类型 | 备货 / 代理 / 销售（三种类型决定审批流和订单生成方式） |
| 订单分支 | 销售订单（自动生成）、代理商订单（**定期汇总**）、代理商备货订单、订单变更 |
| 代码 | `ClueContractController` / `AgentContractController` → `ClueContractServiceImpl`；审批走 `process` 包 |
| 表 | `t_clue_contract`、`t_clue_contract_detail`、`t_clue_contract_bill`、`t_agreement`、`t_goods_order` |

### 7. 交付管理
| 项 | 内容 |
|---|---|
| 主线 | 发货申请（**选择订单，可多批次发货**）→ 客户收货签字确认 → 订单管理 |
| 含硬件 | 订单管理 → 硬件发货 →（仓库发货 / 供应商代发）→ 常规采购 / 供应商直发；另有退换货 |
| 安装 | 安装管理 → 安装派单 |
| 代码 | `GoodsDeliverController`、`AppGoodsOrderController`、`AppGoodsInstallController`、`AppGoodsInventoryController` |
| 表 | `t_goods_deliver`、`t_goods_deliver_detail`、`t_goods_order`、`t_goods_order_detail`、`t_goods_sub_order`、`t_goods_sub_order_detail`、`t_goods_inventory_detail`、`t_goods_sub_install` |

> 发票/回款在这条线上是"订单开票、订单回款"的挂载点，不是独立发起。

### 8. 财务管理
| 项 | 内容 |
|---|---|
| 动作 | 开票申请 → 开票 → 回款登记 → **核销**（回款核销到合同/发票）→ 应收应付 |
| 代码 | `PaymentReceiptController`、`PaymentReceiptWriteOffController`、`PaymentCollectionController`、`PaymentController` |
| 表 | `t_payment_receipt`、`t_payment_receipt_contract`、`t_payment_collection`、`t_payment_collection_contract`、`t_payment_contract` |

> 核销是财务的核心动作：一笔回款可以核销多张合同/发票，所以是 **t_payment_receipt_contract 关联表**，不是一个字段。

### 9. 数据管理（台账与业绩）
| 项 | 内容 |
|---|---|
| 动作 | 合同台账、回款台账、业绩归属、达成率 |
| 代码 | `LedgerServiceImpl`、`DataBoardServiceImpl`、`BusinessMapServiceImpl` |
| 表 | `t_clue_contract`（签单）、`t_payment_receipt`（回款）、`t_process_result`（业绩归属） |

## 三条贯穿全链路的铁律

### 铁律 1：撞单判定
- 线索和商机**各自**有撞单规则开关：`clueManage.clueCollisionRule`、`businessManage.businessCollisionRule`（`CommonEnum.java:949/973`）。
- 取值：**1 = 允许保存数据，2 = 不允许保存数据**。
- 撞单类型按工程的锁定状态分两种：**保护期**、**锁定期**（`DictEnum.java:3483-3484`）。
- 实现：`BusinessRepeatServiceImpl.checkBusinessRepeatInfo()` / `saveBusinessRepeat()`；撞单关系落在 `t_clue_business_repeat`，主表仍是 `t_clue_business`。

### 铁律 2：保护期 / 锁定期
工程被谁"锁"住，决定了别人能不能碰。状态就三个：已锁定 / 保护期内 / 无。线索分配、商机转化、撞单判定全都读它。

### 铁律 3：回收
线索转化失败、商机失单、超时未跟进，最终都回到**线索池**。回收是**定时任务**驱动的（`ClueRecyclingScheduleServiceImpl`），不是操作按钮直接触发。

## 新人五问：该看哪篇

| 你想知道 | 看这篇 |
|---|---|
| 线索怎么分给我、为什么没分 | 《线索全生命周期》 |
| 商机阶段怎么变、撞单是什么 | 《商机与撞单判定》 |
| 报价怎么算、超价怎么审 | 《报价流程（含底价与超额报价审批）》 |
| 合同怎么来的、审批怎么走 | 《合同与审批脊柱》 |
| 发货/安装/退换货怎么走 | 《履约与交付》 |

## 常见问题

- **Q：一条线索最多能走到哪一步？** 走到回款。线索本身在转商机后不再单独改状态，后续由商机/合同/回款串联。
- **Q：为什么商机转化不了？** 两个前置条件：撞单未通过、或没有至少一次有效跟进。
- **Q：合同和订单什么关系？** 合同审批通过后生成订单，订单才是发货的依据。
