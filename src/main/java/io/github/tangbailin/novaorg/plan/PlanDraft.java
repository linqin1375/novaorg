package io.github.tangbailin.novaorg.plan;

import io.github.tangbailin.novaorg.domain.Complexity;

import java.util.List;

/** Raw planner output before it is turned into staffed, budgeted work orders. */
public record PlanDraft(String rationale, Complexity complexity, List<String> roleIds, String intent, String orderId) {
    public PlanDraft(String rationale,Complexity complexity,List<String> roles) { this(rationale,complexity,roles,"LEGACY",null); }
}
