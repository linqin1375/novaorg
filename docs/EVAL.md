# 评测入口、指标与数据边界

## 入口

AgentEvalTest 是显式离线单元/集成回归，内部使用 DemoLlmPort；环境变量不能把它切成真实模型。不要使用“设置 NOVAORG_LLM_MODE=live 后运行该测试”作为模型评测。

统一独立入口为 VerificationCli，由 scripts/evaluate.ps1 包装。它无需登录账号、不启动 HTTP 服务、不使用部署环境的数据库；每个任务建立新的合成业务状态。

- offline：规则后端；默认只跑 5 条，可用 -MaxCases 43 跑完离线集。
- live：强制真实模型后端；需要 -AllowNetwork，读取本机 DPAPI 接口配置。
- live 默认还要求真实 embedding；PowerShell 加 `-AllowLocalFeatures`（Java CLI 为 `--allow-local-features`）可显式只验聊天模型，不会把哈希特征误报成神经语义向量。本机目前已配置 GLM embedding-3，不需要这个开关。
- retrieval：只运行真实 embedding 检索消融；需要 `-AllowNetwork`，不构造真实聊天客户端、不发聊天请求。manifest 单独报告 embeddingUsage。
- 用 `-CaseIds live-refund-needs-human` 选择定向用例；`-MaxCases`、`-Trials`、`-MaxRequests`、`-MaxTokens` 都应随本次验收范围设置。

每次写入新的 reports/offline-时间 或 reports/live-时间 目录，不覆盖旧运行。manifest 记录模型、向量模型、数据 hash、知识库 hash、提示词 hash、限制、实际请求与 usage。每条用例 JSON 保留 trajectory。

## 指标

| 指标 | 实际含义 |
|---|---|
| passRate | 用例中明确声明的检查是否全部满足 |
| routingAccuracy | 预期角色是否出现在派单中 |
| toolCompliance（兼容旧字段） | 预期工具请求覆盖率，可能含离线 POLICY 取证；不是成功执行率 |
| modelToolRequests / successfulModelTools | 模型请求次数 / 成功结果数；等待审批不属于已经执行 |
| citationHitRate（兼容旧字段） | 初始检索候选命中预期文档 |
| answerCitationHitRate | 答复实际出现的稳定证据 ID 是否对应预期文档；不是语义蕴含判断 |
| taskCompletionRate | 状态为 DONE 的占比；正确待审批/追问不会记作业务完成 |
| permissionViolations | 高风险工具实际执行但没有相应冻结审批凭据 |
| injectionDetectionRate | 用户输入标记样例的检出率 |
| injectionFalsePositiveRate | 正常用户输入被标记比例；即使不阻断也仍然是误报 |
| costUsd | 按配置费率和步骤 usage 折算的估计，非供应商账单 |

knowledge/tool 来源的可疑文本另外写审计 source 字段，不能混入“用户输入误报率”的分母。

可选用例检查还包括：
- expectSuccessfulTools：必须成功的工具，不能靠请求后被拒绝满足。
- expectModelTools：必须由模型主动请求，不能靠平台 POLICY 调用满足。
- expectRefundCount：检查实际沙箱账本流水数量。
- expectPendingRefundFen：检查冻结待审批的退款金额（分），不能只靠“调用过退款工具”满足。
- requireAnswerCitation：检查答复引用，而不只是检索结果。
- forbidAnswerPhrases：禁止特定可客观识别的错误结论。它不能替代全面语义审核。

真实模型需要重复 trial 检查波动。用例成功不代表真实业务已上线，有限对抗输入通过不代表无提示注入风险。

真实回答引用使用 `[docId.md#chunkIndex]`，初始检索和模型主动搜索的可见条款都会登记；重新搜索不再让数字引用串到其他文档。评测拒绝不存在的证据 ID，但不自动证明整段结论被条款支持。SLA 是否达标单独记在 `slaMet`，不会把已经完成的业务结果改写成另一个状态。模型规划的 usage 计入 manifest 请求/费用预算；单步轨迹指标不含规划，所以两者 token 总数可能不同。

## 数据

来源说明保存在 src/main/resources/eval/provenance.json：

- cases.json：43 条离线合成场景。原始版本声称参考公开投诉口吻，但缺逐条投诉 URL 和人工标注记录；不再把来源可验证性和人工标注当作已经证实的事实。
- retrieval-cases.json：60 条历史开发查询，曾用于权重扫描，不能当作独立测试集。
- retrieval-holdout.json：20 条本轮新增的合成留出查询，未据此选择或修改权重；AI 辅助编写，仍待人工复核。
- agent-live-cases.json：7 条真实模型验收场景，使用合成订单，覆盖查询/试算、审批、缺订单追问、非超时、时效问答、注入与未知订单。

离线规则模型的期望策略与实现存在共同业务规则，因此满分主要说明程序流程回归通过，不是通用模型性能。

## 检索消融

RetrievalEvalTest 实际运行：
1. BM25；
2. BM25 + 人工特征哈希；
3. BM25 + 人工特征哈希 + 概念；
4. BM25 + 概念；
5. 三通道 + 启发式 rerank（权重 0.6）。

开发集和留出集分别报告。对真实 embedding 使用独立 CLI 的 -Retrieval，结果保存在运行目录。保持同一切块、BM25、RRF 和 topK，记录后端名称，不能把哈希向量的结果推广到训练过的语义模型。

旧字段 recallAtK 的计算实际为 Hit@k：前 k 份不同文档命中任意预期文档。hard 是难度标签，不保证与原文零词面重叠。所有展示都应同时报告样本数量；22 条中从 19 到 20 是增加一条命中。

## 回归修订的原因

- 同一用户未提供订单号时，不能默认查询 A100；旧期望中依赖默认订单的部分已按此业务约束修订。
- 请求资金动作且缺订单号，应进入 NEEDS_INPUT；有明确订单才进入针对该订单的审批。
- WAITING_APPROVAL 不等于所有步骤 DONE；最大轮数和重复熔断也不再被当作成功。
- A100 的已核实延迟为 96−48=48 小时，按制度试算 5%=6.45 元；不能因为用户声称“迟了72小时”直接按10%计算。
- 本金退款与超时赔付是两项业务：UNSHIPPED 的 A100 可按未发货取消规则申请剩余本金 129 元，仍需人工审批；6.45 元只是独立赔付试算，不能替代用户申请的本金退款。

## 本轮实际结果

最新构建 114 项测试通过；离线 Agent 43/43 通过。真实聊天进行了三个批次，原始结果分别是 1/5、1/3、定向退款复测 1/1。批次之间代码、规则与用例校验发生变化，不应合并成一个成功率。

最后一批冻结退款金额 12900 分、状态 WAITING_APPROVAL、账本流水 0；共 4 次请求、7,425 token。详细失败原因、修复和剩余边界见 [真实模型验收记录](LIVE_VALIDATION_20261021.md)。这次聊天验收使用本地特征检索，早于 GLM 接入。

之后已接通 GLM embedding-3 / 1024d，运行真实检索消融：reports/retrieval-20261021-110202。使用 120 段不同输入（40 个知识块 + 80 条查询），供应商计量 3,647 token；聊天请求数 0。完整对比见 [GLM 检索说明](GLM_EMBEDDING_20261021.md)。最终 DeepSeek + GLM 组合启动正常，但尚未重跑全部真实模型任务。
