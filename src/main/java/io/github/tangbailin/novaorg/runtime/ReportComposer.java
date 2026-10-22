package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.Citation;
import io.github.tangbailin.novaorg.domain.Role;
import io.github.tangbailin.novaorg.domain.TaskPlan;
import io.github.tangbailin.novaorg.domain.ToolCallRecord;
import io.github.tangbailin.novaorg.org.RoleCatalog;

import java.util.List;

/** Renders the deliverable a human actually reads: who did what, with which evidence. */
public final class ReportComposer {

    private final RoleCatalog roleCatalog;

    public ReportComposer(RoleCatalog roleCatalog) {
        this.roleCatalog = roleCatalog;
    }

    public String compose(TaskRun run, List<StepResult> results, boolean waitingApproval) {
        TaskPlan plan = run.plan();
        StringBuilder sb = new StringBuilder();
        sb.append("# 交付报告\n\n");
        sb.append("- 目标：").append(run.goal()).append('\n');
        sb.append("- 复杂度：").append(plan == null ? "-" : plan.complexity()).append('\n');
        sb.append("- 派单理由：").append(plan == null ? "-" : plan.rationale()).append('\n');
        sb.append("- 参与部门：").append(results.stream()
                .map(r -> roleOf(r.roleId()).department() + "/" + roleOf(r.roleId()).name())
                .distinct()
                .reduce((a, b) -> a + "、" + b).orElse("-")).append('\n');
        sb.append("- 状态：").append(run.failureReason()!=null ? "失败或被驳回："+run.failureReason() : waitingApproval ? "等待审批" : !run.allStepsDone() ? "待恢复执行" : (run.slaMet() ? "已完成（SLA 达标）" : "已完成（SLA 未达标）"))
                .append('\n');
        sb.append("- 成本：").append(run.tokens()).append(" tokens，")
                .append("延迟 ").append(run.totalLatencyMs()).append(" ms，工具调用 ")
                .append(run.toolCalls()).append(" 次（拒绝 ").append(run.denials()).append(" 次）\n");

        if (waitingApproval) {
            sb.append("- 待审批原因：").append(run.pendingApprovalReason()).append('\n');
        }
        if(run.awaitingInput()) sb.append("- 需要补充信息：").append(run.inputQuestion()).append('\n');

        sb.append("\n## 分部门交付\n");
        for (StepResult result : results) {
            Role role = roleOf(result.roleId());
            sb.append("\n### ").append(role.department()).append(" · ").append(role.name())
                    .append("（").append(result.stepId()).append("）\n");
            sb.append(result.stopReason()==StopReason.NEEDS_INPUT ? "**等待补充**："+result.output()
                    : result.succeeded() ? result.output() : "**未完成**：" + result.failureReason());
            sb.append("\n停止原因：").append(result.stopReason());
            sb.append('\n');

            if (!result.citations().isEmpty()) {
                java.util.List<String> labels = new java.util.ArrayList<>();
                int i = 1;
                for (Citation c : result.citations()) {
                    labels.add("[%d] %s（%s, score=%.3f）".formatted(
                            i++, c.title(), c.namespace(), c.score()));
                }
                sb.append("\n引用：").append(String.join("、", labels)).append('\n');
            }

            if (!result.toolCalls().isEmpty()) {
                sb.append("\n工具调用：\n");
                for (ToolCallRecord call : result.toolCalls()) {
                    sb.append("- ").append(call.allowed() ? "✅" : "⛔").append(' ')
                            .append(call.toolName())
                            .append(call.allowed() ? (call.repeated()?"（幂等重放，未再次执行）":"（已执行）") : "（被拒绝：" + call.denyReason() + "）")
                            .append('\n');
                }
            }
        }

        sb.append("\n## 组织与治理\n")
                .append("- 每次工具调用均经过权限网关（RoleGuard），拒绝记录已写入审计日志。\n")
                .append("- 高风险操作（风险等级 HIGH）必须经审批放行，未放行时系统拒绝执行而非降级绕过。\n")
                .append("- 知识检索限定在角色可读的命名空间内，跨部门知识默认不可见。\n");
        return sb.toString();
    }

    private Role roleOf(String roleId) {
        return roleCatalog.find(roleId).orElseGet(() -> new Role(roleId, roleId, "未知部门",
                "-", "-", java.util.Set.of(), java.util.Set.of(), java.util.List.of(), 0));
    }
}
