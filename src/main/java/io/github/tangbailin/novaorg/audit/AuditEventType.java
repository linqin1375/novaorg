package io.github.tangbailin.novaorg.audit;

/** Everything worth reconstructing after the fact. */
public enum AuditEventType {
    INPUT_REQUIRED,
    INPUT_SUPPLIED,
    TASK_PLANNED,
    STEP_ASSIGNED,
    STEP_STARTED,
    STEP_FINISHED,
    STEP_FAILED,
    TOOL_CALL_ALLOWED,
    TOOL_CALL_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    KNOWLEDGE_RETRIEVED,
    /** 检测到疑似 Prompt 注入：记录，不阻断——下游的权限与审批才是真正的边界。 */
    PROMPT_INJECTION_SUSPECTED,
    /** Agent 决策循环被强制收敛：重复调用、步数超限或 token 预算耗尽。 */
    AGENT_LOOP_GUARD,
    SLA_BREACHED,
    /** Exporting the audit trail is itself an auditable action. */
    AUDIT_EXPORTED
}
