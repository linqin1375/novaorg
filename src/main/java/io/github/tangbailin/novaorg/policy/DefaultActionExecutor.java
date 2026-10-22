package io.github.tangbailin.novaorg.policy;

import io.github.tangbailin.novaorg.audit.AuditEvent;
import io.github.tangbailin.novaorg.audit.AuditEventType;
import io.github.tangbailin.novaorg.audit.AuditLog;
import io.github.tangbailin.novaorg.domain.PermissionDecision;
import io.github.tangbailin.novaorg.domain.ToolCall;
import io.github.tangbailin.novaorg.domain.ToolResult;
import io.github.tangbailin.novaorg.domain.ToolSpec;
import io.github.tangbailin.novaorg.knowledge.KnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.SearchHit;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.tools.BusinessSystems;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Authorize against role and step scope, execute a simulated tool, and return an auditable result.
 * Refund idempotency is owned by the database ledger, not an in-process call cache.
 */
public final class DefaultActionExecutor implements ActionExecutor {

    private final ToolCatalog toolCatalog;
    private final RoleCatalog roleCatalog;
    private final RoleGuard roleGuard;
    private final KnowledgeBase knowledgeBase;
    private final BusinessSystems business;
    private final AuditLog auditLog;

    public DefaultActionExecutor(ToolCatalog toolCatalog, RoleCatalog roleCatalog, RoleGuard roleGuard,
                                 KnowledgeBase knowledgeBase, BusinessSystems business, AuditLog auditLog) {
        this.toolCatalog = toolCatalog;
        this.roleCatalog = roleCatalog;
        this.roleGuard = roleGuard;
        this.knowledgeBase = knowledgeBase;
        this.business = business;
        this.auditLog = auditLog;
    }

    @Override
    public Decision invoke(String taskId, String stepId, ToolCall call, boolean approvalGranted) {
        ToolSpec spec = toolCatalog.find(call.toolName()).orElse(null);
        if (spec == null) {
            return new Decision(ToolResult.error(RoleGuard.DENY_UNKNOWN_TOOL, "未登记的工具"),
                    false, null, RoleGuard.DENY_UNKNOWN_TOOL, false);
        }

        List<String> scope = roleCatalog.find(call.requestedByRole())
                .map(r -> toolCatalog.forRolePermissions(r.permissions()).stream().map(ToolSpec::name).toList()).orElse(List.of());
        return invoke(taskId, stepId, call, approvalGranted, scope);
    }

    @Override
    public Decision invoke(String taskId, String stepId, ToolCall call, boolean approvalGranted, List<String> scope) {
        ToolSpec spec = toolCatalog.find(call.toolName()).orElse(null);
        if (spec == null) return new Decision(ToolResult.error("UNKNOWN_TOOL", "未登记工具"), false, null, "UNKNOWN_TOOL", false);
        // The platform owns business keys: one refund per task/order, independent of model retries.
        String fingerprint = call.toolName().equals(ToolCatalog.REFUND_EXECUTE)
                ? taskId + "|refund|" + strArg(call.arguments(), "orderId", "")
                : taskId + "|" + fingerprint(stepId, call);
        PermissionDecision decision = roleGuard.check(taskId, stepId, call, scope, approvalGranted);
        if (!decision.allowed()) {
            String code = decision.reason().split(":", 2)[0];
            if (RoleGuard.DENY_NEEDS_APPROVAL.equals(code)) {
                auditLog.record(AuditEvent.of(taskId, stepId, call.requestedByRole(),
                        AuditEventType.APPROVAL_REQUIRED,
                        "高风险操作已拦截，等待人工放行：" + call.toolName(),
                        Map.of("tool", call.toolName(), "risk", spec.risk().name())));
                return new Decision(ToolResult.error("APPROVAL_REQUIRED", decision.reason()),
                        true, fingerprint, decision.reason(), false);
            }
            return new Decision(ToolResult.error(code, decision.reason()),
                    false, fingerprint, decision.reason(), false);
        }

        long start = System.nanoTime();
        ToolResult result;
        try { result = execute(taskId, stepId, call, spec, fingerprint); }
        catch (IllegalArgumentException | ArithmeticException e) { result = ToolResult.error("BAD_ARGUMENTS", e.getMessage()); }
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new Decision(result, false, fingerprint, result.success() ? null : result.errorCode(), result.success() && result.payload().contains("幂等键命中"));
    }

    private ToolResult execute(String taskId, String stepId, ToolCall call, ToolSpec spec, String key) {
        Map<String, Object> a = call.arguments() == null ? Map.of() : call.arguments();
        String role = call.requestedByRole();
        List<String> namespaces = roleCatalog.find(role)
                .map(r -> List.copyOf(r.knowledgeNamespaces()))
                .orElse(List.of());

        return switch (call.toolName()) {
            case ToolCatalog.CLARIFY_INPUT -> ToolResult.ok(strArg(a, "question", "请补充订单号及具体诉求。"));
            case ToolCatalog.KNOWLEDGE_SEARCH -> {
                int topK = Math.max(1,Math.min(5,intArg(a, "topK", 3)));
                String query = strArg(a, "query", "");
                String namespace=strArg(a,"namespace","");
                if(!namespace.isBlank() && !namespaces.contains(namespace)) yield ToolResult.error("INVALID_NAMESPACE","仅允许这些命名空间："+namespaces);
                List<SearchHit> hits = knowledgeBase.search(query, namespace.isBlank()?namespaces:List.of(namespace), topK);
                auditLog.record(AuditEvent.of(taskId, stepId, role,
                        AuditEventType.KNOWLEDGE_RETRIEVED,
                        "检索命中 " + hits.size() + " 条",
                        Map.of("query", query, "namespaces", namespaces)));
                yield ToolResult.ok(io.github.tangbailin.novaorg.persistence.JsonCodec.write(Map.of("hits",hits.stream().map(h->Map.of(
                        "evidenceId",h.chunk().docId()+"#"+h.chunk().chunkIndex(),"docId",h.chunk().docId(),
                        "chunkIndex",h.chunk().chunkIndex(),"namespace",h.chunk().namespace(),"title",h.chunk().title(),"text",h.chunk().text())).toList())));
            }
            case ToolCatalog.ORDER_DETAIL -> {
                var order=business.requireOrder(strArg(a,"orderId",""));
                com.fasterxml.jackson.databind.node.ObjectNode data=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(order);
                data.put("delayHours",order.delayHours());data.put("remainingRefundFen",order.paidFen()-order.refundedFen());
                data.put("hoursMeaning","UNSHIPPED=付款后等待时长；DELIVERED=完成履约耗时；delayHours为已核算超时");
                yield ToolResult.ok(data.toString());
            }
            case ToolCatalog.CUSTOMER_PROFILE -> {
                var order=business.findCustomer(strArg(a,"customerId","")).orElseThrow(()->new IllegalArgumentException("CUSTOMER_NOT_FOUND"));
                yield ToolResult.ok("沙箱客户 "+order.customerId()+"｜30天索赔 "+order.claimsLast30Days()+"次｜高风险 "+order.highRisk());
            }
            case ToolCatalog.TICKET_CREATE -> ToolResult.ok(
                    "沙箱工单草稿（未连接真实工单系统）：" + strArg(a, "subject", "未命名工单")
                            + "（幂等键 " + key.substring(0, Math.min(12, key.length())) + "）");
            case ToolCatalog.USAGE_ANALYTICS -> ToolResult.ok("""
                    指标 %s｜区间 %s｜下单转化率 5.8%%（环比 -0.7pt）｜支付转化率 78%%｜退货率 5.1%%｜赔付率 0.62%%
                    """.formatted(strArg(a, "metric", "conversion"), strArg(a, "range", "近 7 天")).trim());
            case ToolCatalog.COMPENSATION_CALC -> {
                var order=business.requireOrder(strArg(a,"orderId",""));
                int rate=!"UNSHIPPED".equals(order.fulfillmentStatus())?0:order.delayHours()>72?10:order.delayHours()>24?5:0;
                long cap=rate==10?20_000L:5_000L;
                long amountFen=Math.min(order.paidFen()*rate/100,cap);
                yield ToolResult.ok("""
                        试算结果：订单 %s，超时 %d 小时；按实付金额 %d%% 计算，单笔上限 %.2f 元；本单试算赔付 %s 元；
                        授权判定：%s；风控判定：%s
                        """.formatted(order.orderId(),order.delayHours(),rate,cap/100.0,"%.2f".formatted(amountFen / 100.0),
                        amountFen <= 30_000L ? "二级（仍须人工审批后模拟执行）" : "三级（必须审批）",
                        order.highRisk() ? "命中高风险，必须转人工审核" : "未命中高风险").trim());
            }
            case ToolCatalog.REFUND_EXECUTE -> {
                String orderId=strArg(a,"orderId","");
                business.requireOrder(orderId);
                long amountFen = new java.math.BigDecimal(String.valueOf(a.get("amount"))).movePointRight(2).longValueExact();
                if (amountFen <= 0) {
                    yield ToolResult.error(RoleGuard.DENY_BAD_ARGUMENTS, "退款金额必须大于 0");
                }
                boolean moved = business.executeRefund(key, orderId, amountFen);
                yield ToolResult.ok((moved ? "模拟退款已记账：" : "退款未重复执行（幂等键命中）：")
                        + "%.2f 元".formatted(amountFen / 100.0)
                        + "｜累计退款 %.2f 元｜幂等键 %s".formatted(
                        business.requireOrder(orderId).refundedFen() / 100.0, key.substring(0, Math.min(16, key.length()))));
            }
            case ToolCatalog.FINANCE_COST -> ToolResult.ok("""
                    预算测算：方案预算 %s｜预留 10%% 超发缓冲｜占用后剩余额度充足；
                    成本红线检查：单均履约成本占单均毛利 52%%（红线 60%%），未超线
                    """.formatted(strArg(a, "plan", "未命名方案")).trim());
            case ToolCatalog.CAMPAIGN_PUBLISH -> ToolResult.ok(
                    "活动配置已发布：audience=" + strArg(a, "audience", "-")
                            + "｜budget=" + a.getOrDefault("budget", "-")
                            + "（审批后执行，留痕已记录）");
            case ToolCatalog.AUDIT_QUERY -> {
                String typeFilter = strArg(a, "eventType", "");
                List<io.github.tangbailin.novaorg.audit.AuditEvent> events = typeFilter.isBlank()
                        ? auditLog.byTask(taskId)
                        : auditLog.byTask(taskId).stream().filter(e -> e.type() == safeType(typeFilter)).toList();
                yield ToolResult.ok(events.stream()
                        .limit(20)
                        .map(e -> "- [%s] %s / %s：%s".formatted(
                                e.type().name(),
                                e.roleId() == null ? "-" : e.roleId(),
                                e.stepId() == null ? "-" : e.stepId(),
                                e.summary()))
                        .reduce((x, y) -> x + "\n" + y)
                        .orElse("未查询到审计记录"));
            }
            case ToolCatalog.APPROVAL_REQUEST -> {
                String reason = strArg(a, "reason", "高风险操作需放行");
                auditLog.record(AuditEvent.of(taskId, stepId, role,
                        AuditEventType.APPROVAL_REQUIRED, "已提交审批请求：" + reason));
                yield ToolResult.ok("审批请求已提交（审批人：管理员）｜原因：" + reason);
            }
            default -> ToolResult.error("NO_HANDLER", "工具已授权但未绑定实现：" + call.toolName());
        };
    }

    private AuditEventType safeType(String raw) {
        try {
            return AuditEventType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return AuditEventType.TOOL_CALL_ALLOWED;
        }
    }

    private static String renderHits(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return "知识库中未检索到依据（不得编造）";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SearchHit hit : hits) {
            sb.append('[').append(i++).append("] ")
                    .append(hit.chunk().title())
                    .append("（score=%.3f）\n".formatted(hit.score()))
                    .append(hit.chunk().text().replaceAll("\\s+", " ").trim())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private static String fingerprint(String stepId, ToolCall call) {
        return stepId + '|' + call.toolName() + '|' + new LinkedHashMap<>(call.arguments() == null ? Map.of() : call.arguments());
    }

    private static String strArg(Map<String, Object> a, String key, String fallback) {
        Object v = a.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int intArg(Map<String, Object> a, String key, int fallback) {
        Object v = a.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleArg(Map<String, Object> a, String key, double fallback) {
        Object v = a.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? fallback : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
