package io.github.tangbailin.novaorg.llm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, deterministic model backend.
 *
 * <p>It is a rule-based stand-in, not a language model - and that is the point:
 * <ul>
 *   <li>the project boots and demos with <b>no API key and no network</b>;</li>
 *   <li>the evaluation suite is <b>reproducible</b> (same input, same output, same metrics);</li>
 *   <li>it lets the orchestration / permission / audit / SLA layers be tested in isolation,
 *       which is where the engineering actually lives.</li>
 * </ul>
 *
 * <p>Every response is labelled with a ceiling note so nobody mistakes a demo run for a
 * real model run: the API and the eval report both expose {@link #id()}.
 */
public final class DemoLlmPort implements LlmPort {

    private static final Pattern ROLE = Pattern.compile("\\[ROLE:([a-z_]+)]");
    private static final Pattern QUESTION = Pattern.compile("用户目标[:：]\\s*(.+)");
    /** 用户目标现在被 PromptGuard 包在分隔符里；优先从这个块取，避免被后续追问污染。 */
    private static final Pattern ISOLATED = Pattern.compile(
            "<<USER_INPUT>>\\s*\\n?(.*?)\\n?\\s*<</USER_INPUT>>", Pattern.DOTALL);
    /** 这一轮已经看到上一轮的工具结果：真实模型此时会收敛，演示后端必须同样收敛。 */
    private static final String OBSERVATION_MARK = "【工具执行结果】";

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.nanoTime();
        String system = request.systemPrompt() == null ? "" : request.systemPrompt();
        String user = request.userPrompt() == null ? "" : request.userPrompt();
        boolean observed = user.contains(OBSERVATION_MARK);

        String text;
        if (system.contains("[PLANNER]")) {
            text = plannerReply(user);
        } else {
            Matcher m = ROLE.matcher(system);
            String roleId = m.find() ? m.group(1) : "unknown";
            text = roleReply(roleId, system, user, observed);
        }

        long latency = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        int in = estimateTokens(system) + estimateTokens(user);
        int out = estimateTokens(text);
        return LlmResponse.of(text, in, out, latency, id());
    }

    @Override
    public String id() {
        return "demo-deterministic-rules";
    }

    // ------------------------------------------------------------------ planner

    /**
     * Mirrors what the real planner prompt asks for. The keyword rules below are the
     * deterministic counterpart of "the model decides who is accountable".
     */
    private String plannerReply(String user) {
        String goal = extractGoal(user);
        String g = goal.toLowerCase(Locale.ROOT);

        String roles;
        String complexity;
        String rationale;

        boolean auditLike = containsAny(g, "审计", "越权", "违规", "合规审查", "权限矩阵", "授权");
        boolean refundLike = containsAny(g, "赔付", "退款", "补偿", "索赔", "超时未发货", "投诉", "退货");
        boolean marketingLike = containsAny(g, "活动", "营销", "触达", "预算方案", "拉新", "转化提升");
        boolean analysisLike = containsAny(g, "指标", "转化率", "退货率", "归因", "下降", "分析");

        boolean policyQuestion = containsAny(g, "咨询", "知识问答", "规则是什么", "制度原文", "可以同时");
        if (policyQuestion && !containsAny(g, "立即退款", "执行退款", "要求赔付")) {
            roles = "customer_service_agent";
            complexity = "SIMPLE";
            rationale = "制度咨询只检索客服可读的知识，不生成资金操作。";
        } else if (auditLike && refundLike) {
            roles = "compliance_officer,customer_service_agent,compensation_specialist,delivery_manager";
            complexity = "COMPLEX";
            rationale = "涉及资金操作与越权风险，先由合规部裁定授权边界，再由客服确认事实、赔付专员给方案，最后交付经理放行。";
        } else if (auditLike) {
            roles = "compliance_officer";
            complexity = "SIMPLE";
            rationale = "单一的合规审查诉求，由合规部独立完成。";
        } else if (marketingLike) {
            roles = "operations_analyst,marketing_specialist,finance_analyst";
            complexity = "COMPLEX";
            rationale = "活动方案需要运营先给数据依据、市场出方案、财务核预算，属于跨部门协作。";
        } else if (analysisLike && !refundLike) {
            roles = "operations_analyst";
            complexity = "SIMPLE";
            rationale = "纯指标归因问题，运营部可独立闭环。";
        } else if (refundLike) {
            roles = "customer_service_agent,compensation_specialist";
            complexity = "MODERATE";
            rationale = "客诉类任务按流程派单：客服先确认事实与制度依据，赔付专员再在授权额度内给出方案。";
        } else {
            roles = "customer_service_agent";
            complexity = "SIMPLE";
            rationale = "未命中跨部门规则，按咨询类任务交给客服部处理。";
        }

        return """
                {
                  "rationale": "%s",
                  "complexity": "%s",
                  "roles": "%s"
                }
                """.formatted(rationale, complexity, roles);
    }

    private String extractGoal(String user) {
        Matcher block = ISOLATED.matcher(user);
        if (block.find()) {
            return block.group(1).trim();
        }
        Matcher m = QUESTION.matcher(user);
        if (m.find()) {
            return m.group(1).trim();
        }
        return user.trim();
    }

    /**
     * Decide whether this turn should attempt the (high-risk) refund execution.
     *
     * <p>A deterministic backend has to model intent, and intent here is the difference between
     * "what is the refund policy?" and "refund this order now". Getting this wrong is how a
     * knowledge question ends up attempting a money movement - which is exactly the failure mode
     * the permission gate exists to catch, but it is better not to trigger it at all.
     */
    private String refundIntent(String goal, String context, boolean observed) {
        if (observed) {
            // 已经看到了自己上一轮工具调用的结果：真实模型在此收敛，演示后端必须同样收敛，
            // 否则 Agent 循环会被重复调用熔断器拦下来，评测结果就失真了。
            return "已核对工具返回结果，本轮不再请求新的工具，直接给出结论。";
        }
        boolean actionable = containsAny(goal, "要求", "请执行", "直接", "立即", "马上", "立刻",
                "现在就", "全额退", "发起", "执行赔付", "办理");
        boolean refundish = containsAny(goal, "赔付", "退款", "索赔", "补偿");
        var orderId=io.github.tangbailin.novaorg.tools.OrderReference.extract(goal);
        if(actionable && refundish && orderId.isEmpty()) return "TOOL_CALL {\"name\":\"customer.clarify\",\"arguments\":{\"question\":\"请提供需要处理的订单号，以及退款或赔付的具体原因。\"}}";
        if (actionable && refundish) {
            String id=orderId.orElse("A100");
            var sandbox=new io.github.tangbailin.novaorg.tools.BusinessSystems();
            var order=sandbox.findOrder(id);
            if(order.isEmpty()) return "订单不存在，不能执行资金动作，请核对订单号。";
            long amount=goal.contains("退款")?order.get().paidFen():Math.min(order.get().paidFen()*(order.get().delayHours()>=72?10:order.get().delayHours()>=24?5:0)/100,order.get().delayHours()>=72?20000:5000);
            return """
                    下一步动作：提议模拟退款，必须等待人工核对并审批，当前尚未执行。
                    TOOL_CALL {"name":"refund.execute","arguments":{"orderId":"%s","amount":%s,"reason":"离线演示申请，需人工核对业务依据","idempotencyKey":"offline-rule"}}
                    """.formatted(id,java.math.BigDecimal.valueOf(amount,2).toPlainString());
        }
        if (refundish && containsAny(goal, "审批", "越权", "合规", "跳过")) {
            return """
                    下一步动作：本次涉及超授权额度，按规范应提交审批而非直接执行。
                    TOOL_CALL {"name":"refund.execute","arguments":{"orderId":"A100","amount":520.0,"reason":"超授权额度赔付","idempotencyKey":"refund-A100-escalated"}}
                    """;
        }
        return "说明：本次为制度咨询，不涉及资金动作，因此不发起赔付执行（依据：" + context + "）。";
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ roles

    private String roleReply(String roleId, String system, String user, boolean observed) {
        String context = extractContext(user);
        String goal = extractGoal(user == null ? "" : user);

        return switch (roleId) {
            case "customer_service_agent" -> """
                    【离线规则演示】客服建议：
                    1. 按下面引用的制度核对条件、时效和所需凭证 [1]。
                    2. 涉及订单时以工具查询中展示的模拟数据为准；本回答不证明真实客户事实。
                    3. 如需工单应另行登记；当前模板没有调用工单系统。
                    4. 退款或赔付执行必须由另一登录账号审批，客服文本不能替代授权。

                    交付：reply / citations
                    依据来源：[1] %s
                    用户目标：%s
                    """.formatted(context, goal);

            case "compensation_specialist" -> """
                    【离线规则演示】赔付方案：
                    - 依据条款：赔付与补偿标准 · 超时赔付标准 [1]。
                    - 计算过程：订单实付金额 × 对应超时档位比例，单笔上限约束内取小；生鲜类若涉变质按全额。
                    - 演示口径：订单事实与试算结果以当前工具返回为准，不能把用户描述当作已核实事实。
                    - 当前未连接真实风控服务，实际业务条件需要人工核对。
                    - 留痕要求：记录依据条款、金额、计算过程、执行角色与时间戳 [2]。
                    - 所有退款执行都必须由另一登录账号审批，与金额大小无关。

                    %s
                    交付：decision / amount / basis
                    依据来源：[1] %s
                    用户目标：%s
                    """.formatted(refundIntent(goal, context, observed), context, goal);

            case "operations_analyst" -> """
                    归因分析：
                    - 指标口径：按《核心运营指标口径》取数，赔付率健康区间为 0.3%% 至 0.8%%，超过 1%% 视为异常 [1]。
                    - 对比基准：给出环比与同比；样本量低于 500 单的结论仅作观察项，不作为决策依据 [1]。
                    - 归因假设：一是活动价订单与价保互斥导致客户预期落差；二是超时履约集中在特定仓配线路。
                    - 行动建议（按优先级）：1) 对异常线路做仓配容量复核；2) 对赔付率超标类目做专项抽检；
                      3) 在活动页明示价保与活动价互斥规则。
                    - 口径声明：本结论用于运营决策，不涉及资金动作与客户个人信息。

                    交付：findings / actions
                    依据来源：[1] %s
                    """.formatted(context);

            case "marketing_specialist" -> """
                    活动方案要点：
                    - 目标人群：近 90 天有浏览未下单且未退订的授权客户。
                    - 预算区间：按《营销活动管理规范》，单场不超过 5 万元由市场部负责人审批，并预留 10%% 超发缓冲 [1]。
                    - 触达合规：同一客户每周不超过 2 条、每月不超过 6 条；不使用虚假紧迫话术；退订 24 小时内全渠道生效 [1]。
                    - 优惠叠加：店铺券与平台券可叠加，同类型券不可叠加；活动价与价保互斥必须在下单前明示 [1]。
                    - 转化目标：下单转化率目标对齐行业基准 6%% 至 9%%，并设置对照组。
                    - 复盘要求：活动后 3 个工作日内完成核销率、ROI、异常订单占比复盘 [1]。

                    交付：plan / budget / compliance_check
                    依据来源：[1] %s
                    """.formatted(context);

            case "finance_analyst" -> """
                    财务影响评估：
                    - 成本口径：履约成本含商品成本、物流、支付通道费、客服人力与赔付支出；赔付计入售后服务成本，
                      不得计入营销费用 [1]。
                    - 预算占用：预算一经审批即占用额度，未核销部分在活动结束 7 个自然日后释放；跨季度需做结转说明 [1]。
                    - 红线检查：单均履约成本不得超过单均毛利的 60%%，连续两周超线需提交改善计划 [1]。
                    - 结论：在现有预算缓冲与红线约束内可执行，但需在复盘中同步实际核销率与赔付支出。

                    交付：cost_impact / budget_risk
                    依据来源：[1] %s
                    """.formatted(context);

            case "compliance_officer" -> """
                    合规裁定：
                    - 结论：有条件通过。
                    - 问题一：赔付金额超过 300 元属于三级授权，缺少交付经理审批即执行属于越权操作，
                      违反《权限与审计规范》中的高风险操作条款 [1]。
                    - 问题二：系统不得以"跳过审批"为由直接触发资金类工具，RoleGuard 的拒绝属于设计预期，不是故障 [1]。
                    - 问题三：审计日志必须可还原操作时间、角色、对象、参数摘要与授权依据，本次已具备 [1]。
                    - 整改要求：1) 补充审批记录后再执行；2) 将本次拒绝事件纳入合规事件台账；
                      3) 复核该角色的权限矩阵是否存在过度授权。

                    交付：verdict / issues / remediation
                    依据来源：[1] %s
                    """.formatted(context);

            case "delivery_manager" -> """
                    交付结论：
                    - 已汇总各部门产出，事实、方案、合规三侧均已完成。
                    - 高风险动作（金额超授权额度的赔付）未放行，等待审批；其余交付项满足标准。
                    - SLA：延迟、token 与成本均在预算内，未触发降级。
                    - 未决风险：审批通过后仍需复核是否存在重复索赔。
                    - 后续责任人与时间点：赔付专员在审批通过后 4 小时内执行并回填留痕；
                      合规专员在 5 个工作日内完成整改闭环。

                    交付：summary / open_risks / next_actions
                    """;

            default -> """
                    已完成本步骤的交付物整理，关键结论与依据如下：
                    - 目标：%s
                    - 依据：%s

                    交付字段：%s
                    """.formatted(goal, context, deliverableHint(system));
        };
    }

    private String deliverableHint(String system) {
        int idx = system.indexOf("必须交付的字段");
        if (idx < 0) {
            return "未声明";
        }
        int end = system.indexOf('\n', idx);
        return end < 0 ? system.substring(idx) : system.substring(idx, end);
    }

    /** Pulls the retrieved-context block out of the user prompt so replies can cite it. */
    private String extractContext(String user) {
        int idx = user.indexOf("【知识依据");
        if (idx < 0) {
            return "企业知识库";
        }
        int end = user.indexOf('】', idx);
        String block = end < 0 ? user.substring(idx) : user.substring(end + 1);
        String[] lines = block.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                int close = trimmed.indexOf(']');
                String rest = trimmed.substring(close + 1).trim();
                if (!rest.isEmpty()) {
                    return rest.length() > 60 ? rest.substring(0, 60) : rest;
                }
            }
        }
        return "企业知识库";
    }

    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x2E80) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * 0.7 + other / 4.0);
    }
}
