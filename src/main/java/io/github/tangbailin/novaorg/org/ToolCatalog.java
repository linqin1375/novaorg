package io.github.tangbailin.novaorg.org;

import io.github.tangbailin.novaorg.domain.Permission;
import io.github.tangbailin.novaorg.domain.RiskLevel;
import io.github.tangbailin.novaorg.domain.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tool registry: the company's "systems of record" exposed in the shape of the
 * MCP tool contract ({@code tools/list} -> name + description + parameter schema,
 * {@code tools/call} -> JSON arguments).
 *
 * <p>Two properties matter more than the tool list itself:
 * <ul>
 *   <li>every tool declares the <b>permission</b> it needs, so authorization is data-driven;</li>
 *   <li>every tool declares its <b>risk</b> and whether it is <b>idempotent</b>, which is what
 *       lets the runtime decide "run it", "ask a human", or "safe to retry".</li>
 * </ul>
 */
public final class ToolCatalog {
    public static final String CLARIFY_INPUT = "customer.clarify";

    public static final String KNOWLEDGE_SEARCH = "knowledge.search";
    public static final String CUSTOMER_PROFILE = "customer.profile";
    public static final String TICKET_CREATE = "ticket.create";
    public static final String ORDER_DETAIL = "order.detail";
    public static final String USAGE_ANALYTICS = "usage.analytics";
    public static final String REFUND_EXECUTE = "refund.execute";
    public static final String COMPENSATION_CALC = "compensation.calculate";
    public static final String FINANCE_COST = "finance.cost.impact";
    public static final String CAMPAIGN_PUBLISH = "campaign.publish";
    public static final String AUDIT_QUERY = "audit.query";
    public static final String APPROVAL_REQUEST = "approval.request";

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

    public ToolCatalog() {
        register(new ToolSpec(CLARIFY_INPUT, "缺少订单、凭证或必要信息时暂停任务并向用户追问，禁止猜测",
                Map.of("question", "string"), List.of("question"), Permission.KNOWLEDGE_READ, RiskLevel.LOW, true));
        register(new ToolSpec(KNOWLEDGE_SEARCH, "检索岗位可读制度，返回稳定 evidenceId。namespace 可省略；如指定必须用岗位可读的英文命名空间，不可用中文别名；最多返回5条",
                Map.of("query", "string", "namespace", "string", "topK", "integer"),
                List.of("query"), Permission.KNOWLEDGE_READ, RiskLevel.LOW, true));

        register(new ToolSpec(CUSTOMER_PROFILE, "读取客户档案（脱敏后的等级、生命周期、历史工单数）",
                Map.of("customerId", "string"), List.of("customerId"),
                Permission.CUSTOMER_READ, RiskLevel.MEDIUM, true));

        register(new ToolSpec(ORDER_DETAIL, "读取订单详情（金额、状态、时间线）",
                Map.of("orderId", "string"), List.of("orderId"),
                Permission.CUSTOMER_READ, RiskLevel.LOW, true));

        register(new ToolSpec(TICKET_CREATE, "生成沙箱工单草稿（不连接真实工单系统，不作为已落库凭据）",
                Map.of("subject", "string", "body", "string", "dedupKey", "string"),
                List.of("subject", "body", "dedupKey"),
                Permission.TICKET_WRITE, RiskLevel.MEDIUM, false));

        register(new ToolSpec(USAGE_ANALYTICS, "查询用户行为与活动转化数据（聚合口径，不含个人信息）",
                Map.of("metric", "string", "range", "string"), List.of("metric"),
                Permission.USAGE_ANALYTICS_READ, RiskLevel.LOW, true));

        register(new ToolSpec(REFUND_EXECUTE, "提交退款执行申请：调用后冻结订单、金额和理由并暂停等待人工审批，当前不会动账。符合退款规则时可提出申请，无需模型自行取得审批。幂等键由平台生成",
                Map.of("orderId", "string", "amount", "number", "reason", "string", "idempotencyKey", "string"),
                List.of("orderId", "amount", "idempotencyKey"),
                Permission.REFUND_WRITE, RiskLevel.HIGH, false));

        register(new ToolSpec(COMPENSATION_CALC, "按赔付政策试算赔付金额（只读试算，不产生资金动作）",
                Map.of("orderId", "string", "scenario", "string"), List.of("orderId", "scenario"),
                Permission.COMPENSATION_WRITE, RiskLevel.LOW, true));

        register(new ToolSpec(FINANCE_COST, "财务口径的成本与预算影响测算",
                Map.of("plan", "string", "budgetCode", "string"), List.of("plan"),
                Permission.FINANCE_READ, RiskLevel.LOW, true));

        register(new ToolSpec(CAMPAIGN_PUBLISH, "发布营销活动配置（高风险：需要审批放行）",
                Map.of("campaignId", "string", "audience", "string", "budget", "number"),
                List.of("campaignId", "audience"),
                Permission.CAMPAIGN_WRITE, RiskLevel.HIGH, false));

        register(new ToolSpec(AUDIT_QUERY, "查询审计日志（按任务、角色或事件类型）",
                Map.of("taskId", "string", "roleId", "string", "eventType", "string"),
                List.of(), Permission.COMPLIANCE_AUDIT, RiskLevel.LOW, true));

        register(new ToolSpec(APPROVAL_REQUEST, "向管理员提交审批请求（交付经理专用）",
                Map.of("taskId", "string", "reason", "string"), List.of("taskId", "reason"),
                Permission.APPROVAL_GRANT, RiskLevel.MEDIUM, false));
    }

    private void register(ToolSpec spec) {
        tools.put(spec.name(), spec);
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolSpec> all() {
        return List.copyOf(tools.values());
    }

    public List<ToolSpec> forRolePermissions(java.util.Set<Permission> permissions) {
        return tools.values().stream()
                .filter(t -> permissions.contains(t.requiredPermission()))
                .toList();
    }
}
