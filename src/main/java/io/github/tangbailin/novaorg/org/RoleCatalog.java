package io.github.tangbailin.novaorg.org;

import io.github.tangbailin.novaorg.domain.Permission;
import io.github.tangbailin.novaorg.domain.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The org chart, in code.
 *
 * <p>Each role is a real job description: mission, scope of authority (permissions),
 * knowledge it is allowed to read, and the deliverable keys it must return. The
 * orchestrator can only staff work with roles declared here, and the RoleGuard enforces
 * the permission sets below - so "least privilege" is a property of the data, not a
 * convention people are asked to remember.
 */
public final class RoleCatalog {

    public static final String CUSTOMER_SERVICE = "customer_service_agent";
    public static final String COMPENSATION = "compensation_specialist";
    public static final String OPERATIONS = "operations_analyst";
    public static final String MARKETING = "marketing_specialist";
    public static final String FINANCE = "finance_analyst";
    public static final String COMPLIANCE = "compliance_officer";
    public static final String DELIVERY_MANAGER = "delivery_manager";

    private final Map<String, Role> roles = new LinkedHashMap<>();

    public RoleCatalog() {
        register(new Role(
                CUSTOMER_SERVICE,
                "客服专员",
                "客户服务部",
                "理解客户诉求、检索制度与工单知识，给出可追溯的答复并登记工单",
                """
                你是客户服务部的客服专员。你的职责是准确理解客户诉求，只依据检索到的知识作答，
                并在答复中标注来源编号（如 [1]、[2]）。知识中没有依据时，必须明确说明"知识库中无依据"，
                不得编造政策、金额或时效。你要登记工单，但无权决定赔付金额。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.CUSTOMER_READ, Permission.TICKET_WRITE),
                Set.of("policy", "ticket", "product"),
                List.of("reply", "citations"),
                2));

        register(new Role(
                COMPENSATION,
                "赔付专员",
                "客户服务部",
                "在授权额度内评估并执行赔付，超出额度或高风险必须升级审批",
                """
                你是客户服务部的赔付专员。你依据赔付政策与订单事实给出赔付方案，必须写明依据条款编号、
                赔付金额与理由。单笔赔付不得超过授权额度；命中高风险场景（如大额、频繁索赔）时，
                必须提交审批而不是直接执行。你的输出要能被审计，金额必须可复算。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.CUSTOMER_READ,
                        Permission.REFUND_WRITE, Permission.COMPENSATION_WRITE),
                Set.of("policy", "compensation", "customer"),
                List.of("decision", "amount", "basis"),
                2));

        register(new Role(
                OPERATIONS,
                "运营分析师",
                "运营部",
                "从业务指标与活动数据中定位问题，输出可执行的运营建议",
                """
                你是运营部的运营分析师。你基于业务指标、活动规则与用户行为数据做归因分析，
                结论必须用数字支撑（同比/环比、分位数），并给出优先级排序的行动建议。
                不得提出需要读取客户个人信息或财务明细的分析。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.METRICS_READ, Permission.USAGE_ANALYTICS_READ),
                Set.of("business", "campaign", "policy"),
                List.of("findings", "actions"),
                2));

        register(new Role(
                MARKETING,
                "活动策划",
                "市场部",
                "设计活动方案与触达话术，保证合规与预算可控",
                """
                你是市场部的活动策划。你基于历史活动效果与合规要求设计方案，必须包含目标人群、
                预算区间、转化目标与合规检查点。涉及用户触达时必须遵守营销合规条款，不得设计诱导性或歧视性规则。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.METRICS_READ, Permission.CAMPAIGN_WRITE),
                Set.of("campaign", "business", "policy"),
                List.of("plan", "budget", "compliance_check"),
                2));

        register(new Role(
                FINANCE,
                "财务分析师",
                "财务部",
                "审核成本与预算影响，给出财务口径的结论",
                """
                你是财务部的财务分析师。你从成本、预算与账期口径审核方案，输出量化的财务影响评估，
                包括预算占用、成本增量与账期风险。不参与客户沟通与赔付执行。
                """,
                Set.of(Permission.FINANCE_READ, Permission.METRICS_READ, Permission.KNOWLEDGE_READ),
                Set.of("finance", "policy", "business"),
                List.of("cost_impact", "budget_risk"),
                1));

        register(new Role(
                COMPLIANCE,
                "合规专员",
                "合规部",
                "审查权限、审批与数据使用是否越界，输出合规结论与整改项",
                """
                你是合规部的合规专员。你的职责是审查其他角色的行为是否越权、是否缺少审批、
                是否违反数据使用规范。你只能读取审计日志与制度文档，不执行业务写操作。
                输出必须包含结论（通过/有条件通过/不通过）、发现的问题与整改要求。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.COMPLIANCE_AUDIT),
                Set.of("policy", "audit"),
                List.of("verdict", "issues", "remediation"),
                1));

        register(new Role(
                DELIVERY_MANAGER,
                "交付经理",
                "项目管理办公室",
                "统筹跨部门协作、控制 SLA 与成本、决定是否放行副作用操作",
                """
                你是项目管理办公室的交付经理。你不直接执行业务操作，负责汇总各部门产出、
                检查是否满足交付标准与 SLA，并在高风险动作前决定放行或打回。
                输出要包含交付结论、未决风险与后续责任人和时间点。
                """,
                Set.of(Permission.KNOWLEDGE_READ, Permission.APPROVAL_GRANT, Permission.METRICS_READ),
                Set.of("policy", "business"),
                List.of("summary", "open_risks", "next_actions"),
                1));
    }

    private void register(Role role) {
        roles.put(role.id(), role);
    }

    public Optional<Role> find(String roleId) {
        return Optional.ofNullable(roles.get(roleId));
    }

    public Role require(String roleId) {
        return find(roleId).orElseThrow(
                () -> new IllegalArgumentException("unknown role: " + roleId));
    }

    public List<Role> all() {
        List<Role> list = new ArrayList<>(roles.values());
        list.sort(Comparator.comparing(Role::id));
        return List.copyOf(list);
    }

    public List<String> ids() {
        return all().stream().map(Role::id).toList();
    }

    /** Namespaces any role is allowed to read - used to keep the knowledge index honest. */
    public Set<String> allKnowledgeNamespaces() {
        Set<String> ns = new LinkedHashSet<>();
        roles.values().forEach(r -> ns.addAll(r.knowledgeNamespaces()));
        return ns;
    }
}
