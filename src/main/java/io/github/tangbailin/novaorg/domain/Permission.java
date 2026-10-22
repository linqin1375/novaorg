package io.github.tangbailin.novaorg.domain;

/**
 * Every capability of the system is an explicit, named permission.
 * Nothing is implicit: a role can only reach a tool if it holds the tool's required permission.
 */
public enum Permission {
    KNOWLEDGE_READ("knowledge:read"),
    CUSTOMER_READ("customer:read"),
    REFUND_WRITE("refund:write"),
    COMPENSATION_WRITE("compensation:write"),
    TICKET_WRITE("ticket:write"),
    METRICS_READ("metrics:read"),
    FINANCE_READ("finance:read"),
    USAGE_ANALYTICS_READ("usage:analytics:read"),
    CAMPAIGN_WRITE("campaign:write"),
    CONTRACT_READ("contract:read"),
    RISK_REVIEW("risk:review"),
    COMPLIANCE_AUDIT("compliance:audit"),
    APPROVAL_GRANT("approval:grant");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission ofCode(String code) {
        for (Permission p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown permission code: " + code);
    }
}
