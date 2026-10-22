package io.github.tangbailin.novaorg.policy;

import io.github.tangbailin.novaorg.audit.AuditEvent;
import io.github.tangbailin.novaorg.audit.AuditEventType;
import io.github.tangbailin.novaorg.audit.AuditLog;
import io.github.tangbailin.novaorg.domain.PermissionDecision;
import io.github.tangbailin.novaorg.domain.Role;
import io.github.tangbailin.novaorg.domain.ToolCall;
import io.github.tangbailin.novaorg.domain.ToolSpec;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authorization engine - the "who is allowed to do what" gate that sits in front of
 * every side effect.
 *
 * <p>Checks, in order:
 * <ol>
 *   <li>does the tool exist?</li>
 *   <li>is the tool inside this step's assigned scope? (a role cannot call a tool the
 *       planner never gave it, even if the role's permission set would allow it)</li>
 *   <li>does the role hold the tool's required permission?</li>
 *   <li>does the call carry the parameters the tool declares as required?</li>
 *   <li>is the call high-risk, i.e. does it need an approval token?</li>
 * </ol>
 * Every decision - allow or deny - is written to the audit log with a machine-readable reason.
 */
public final class RoleGuard {

    public static final String DENY_UNKNOWN_TOOL = "TOOL_NOT_REGISTERED";
    public static final String DENY_OUT_OF_SCOPE = "TOOL_OUT_OF_SCOPE";
    public static final String DENY_MISSING_PERMISSION = "MISSING_PERMISSION";
    public static final String DENY_BAD_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String DENY_NEEDS_APPROVAL = "APPROVAL_REQUIRED";

    private final ToolCatalog toolCatalog;
    private final RoleCatalog roleCatalog;
    private final AuditLog auditLog;

    public RoleGuard(ToolCatalog toolCatalog, RoleCatalog roleCatalog, AuditLog auditLog) {
        this.toolCatalog = toolCatalog;
        this.roleCatalog = roleCatalog;
        this.auditLog = auditLog;
    }

    public PermissionDecision check(String taskId, String stepId, ToolCall call,
                                    List<String> stepScopedTools, boolean approvalGranted) {

        ToolSpec spec = toolCatalog.find(call.toolName()).orElse(null);
        if (spec == null) {
            return deny(taskId, stepId, call, DENY_UNKNOWN_TOOL,
                    "工具未在注册中心登记: " + call.toolName());
        }

        if (!stepScopedTools.contains(call.toolName())) {
            return deny(taskId, stepId, call, DENY_OUT_OF_SCOPE,
                    "该步骤未被授予该工具，越权调用: " + call.toolName());
        }

        Role role = roleCatalog.find(call.requestedByRole()).orElse(null);
        if (role == null) {
            return deny(taskId, stepId, call, DENY_MISSING_PERMISSION,
                    "角色未登记: " + call.requestedByRole());
        }
        if (!role.can(spec.requiredPermission())) {
            return deny(taskId, stepId, call, DENY_MISSING_PERMISSION,
                    "角色 " + role.id() + " 缺少权限 " + spec.requiredPermission().code());
        }

        List<String> missing = spec.requiredParameters().stream()
                .filter(p -> call.arguments() == null
                        || call.arguments().get(p) == null
                        || String.valueOf(call.arguments().get(p)).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            return deny(taskId, stepId, call, DENY_BAD_ARGUMENTS,
                    "缺少必填参数: " + String.join(", ", missing));
        }

        if(call.arguments()!=null) for(var entry:call.arguments().entrySet()) {
            String type=spec.parameters().get(entry.getKey());Object value=entry.getValue();
            boolean valid=type!=null && (value==null || switch(type) {
                case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
                case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()==Math.rint(n.doubleValue());
                case "boolean" -> value instanceof Boolean;
                default -> value instanceof String;
            });
            if(!valid) return deny(taskId,stepId,call,DENY_BAD_ARGUMENTS,"参数类型不符或含未登记参数："+entry.getKey());
        }

        if (spec.risk().requiresApproval() && !approvalGranted) {
            return deny(taskId, stepId, call, DENY_NEEDS_APPROVAL,
                    "高风险操作需审批放行: " + spec.name() + " (risk=" + spec.risk() + ")");
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tool", spec.name());
        attrs.put("risk", spec.risk().name());
        attrs.put("permission", spec.requiredPermission().code());
        attrs.put("idempotent", spec.idempotent());
        auditLog.record(AuditEvent.of(taskId, stepId, role.id(),
                AuditEventType.TOOL_CALL_ALLOWED, "放行工具调用 " + spec.name(), attrs));
        return PermissionDecision.allow("允许调用 " + spec.name());
    }

    private PermissionDecision deny(String taskId, String stepId, ToolCall call,
                                    String code, String reason) {
        auditLog.record(AuditEvent.of(taskId, stepId, call.requestedByRole(),
                AuditEventType.TOOL_CALL_DENIED, reason,
                Map.of("tool", call.toolName(), "code", code)));
        return PermissionDecision.deny(code + ": " + reason);
    }
}
