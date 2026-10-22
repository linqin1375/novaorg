package io.github.tangbailin.novaorg.policy;

import io.github.tangbailin.novaorg.audit.AuditEventType;
import io.github.tangbailin.novaorg.audit.InMemoryAuditLog;
import io.github.tangbailin.novaorg.domain.ToolCall;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission gateway is the component most worth testing hard: a regression here is a
 * security incident, not a bug.
 */
class RoleGuardTest {

    private static final String TASK = "t-test";
    private static final String STEP = "step-1";

    private ToolCatalog tools;
    private RoleCatalog roles;
    private InMemoryAuditLog auditLog;
    private RoleGuard guard;

    @BeforeEach
    void setUp() {
        tools = new ToolCatalog();
        roles = new RoleCatalog();
        auditLog = new InMemoryAuditLog();
        guard = new RoleGuard(tools, roles, auditLog);
    }

    private List<String> scopeOf(String roleId) {
        return tools.forRolePermissions(roles.require(roleId).permissions()).stream()
                .map(t -> t.name()).toList();
    }

    @Test
    void allowsACallTheRoleHoldsThePermissionFor() {
        String role = RoleCatalog.COMPENSATION;
        var decision = guard.check(TASK, STEP,
                new ToolCall(ToolCatalog.COMPENSATION_CALC, Map.of("orderId", "A100", "scenario", "timeout"), role),
                scopeOf(role), false);

        assertThat(decision.allowed()).isTrue();
        assertThat(auditLog.byType(AuditEventType.TOOL_CALL_ALLOWED)).hasSize(1);
    }

    @Test
    void deniesAnUnregisteredTool() {
        var decision = guard.check(TASK, STEP,
                new ToolCall("shell.exec", Map.of(), RoleCatalog.COMPENSATION),
                List.of("shell.exec"), false);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).startsWith(RoleGuard.DENY_UNKNOWN_TOOL);
    }

    @Test
    void deniesAToolOutsideTheStepScopeEvenIfTheRoleHasThePermission() {
        String role = RoleCatalog.COMPENSATION;
        var decision = guard.check(TASK, STEP,
                new ToolCall(ToolCatalog.REFUND_EXECUTE,
                        Map.of("orderId", "A100", "amount", 129.0, "idempotencyKey", "k1"), role),
                List.of(ToolCatalog.COMPENSATION_CALC), false);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).startsWith(RoleGuard.DENY_OUT_OF_SCOPE);
    }

    @Test
    void deniesARoleThatLacksTheRequiredPermission() {
        String role = RoleCatalog.CUSTOMER_SERVICE;
        var decision = guard.check(TASK, STEP,
                new ToolCall(ToolCatalog.REFUND_EXECUTE,
                        Map.of("orderId", "A100", "amount", 129.0, "idempotencyKey", "k1"), role),
                List.of(ToolCatalog.REFUND_EXECUTE), false);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).startsWith(RoleGuard.DENY_MISSING_PERMISSION);
    }

    @Test
    void deniesWhenMandatoryArgumentsAreMissing() {
        String role = RoleCatalog.COMPENSATION;
        var decision = guard.check(TASK, STEP,
                new ToolCall(ToolCatalog.REFUND_EXECUTE, Map.of("orderId", "A100"), role),
                List.of(ToolCatalog.REFUND_EXECUTE), false);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).startsWith(RoleGuard.DENY_BAD_ARGUMENTS);
    }

    @Test
    void blocksHighRiskToolsUntilAnApprovalIsGranted() {
        String role = RoleCatalog.COMPENSATION;
        ToolCall call = new ToolCall(ToolCatalog.REFUND_EXECUTE,
                Map.of("orderId", "A100", "amount", 129.0, "idempotencyKey", "k1"), role);
        List<String> scope = List.of(ToolCatalog.REFUND_EXECUTE);

        var blocked = guard.check(TASK, STEP, call, scope, false);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).startsWith(RoleGuard.DENY_NEEDS_APPROVAL);
        assertThat(auditLog.byType(AuditEventType.TOOL_CALL_DENIED)).hasSize(1);

        var released = guard.check(TASK, STEP, call, scope, true);
        assertThat(released.allowed()).isTrue();
    }

    @Test
    void everyDenialIsWrittenToTheAuditLogWithAMachineReadableCode() {
        guard.check(TASK, STEP,
                new ToolCall(ToolCatalog.REFUND_EXECUTE, Map.of(), RoleCatalog.CUSTOMER_SERVICE),
                List.of(ToolCatalog.REFUND_EXECUTE), false);

        var denied = auditLog.byType(AuditEventType.TOOL_CALL_DENIED);
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0).attributes()).containsKey("code");
        assertThat(denied.get(0).taskId()).isEqualTo(TASK);
    }

    @Test
    void customerServiceCannotReachFinanceOrCampaignTools() {
        List<String> scope = scopeOf(RoleCatalog.CUSTOMER_SERVICE);
        assertThat(scope).doesNotContain(ToolCatalog.FINANCE_COST, ToolCatalog.CAMPAIGN_PUBLISH,
                ToolCatalog.REFUND_EXECUTE, ToolCatalog.AUDIT_QUERY);
    }
}
