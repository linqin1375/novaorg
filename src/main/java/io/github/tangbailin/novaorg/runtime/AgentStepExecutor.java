package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.audit.*;
import io.github.tangbailin.novaorg.domain.*;
import io.github.tangbailin.novaorg.knowledge.*;
import io.github.tangbailin.novaorg.llm.*;
import io.github.tangbailin.novaorg.org.*;
import io.github.tangbailin.novaorg.policy.*;
import java.util.*;

/** Bounded model/tool loop. Authorization and money arithmetic remain outside the model. */
public final class AgentStepExecutor implements StepExecutor {
    public static final int DEFAULT_MAX_TURNS=3;
    private final LlmPort llmPort;
    private final RoleCatalog roleCatalog;
    private final ToolCatalog toolCatalog;
    private final RolePromptFactory promptFactory;
    private final KnowledgeBase knowledgeBase;
    private final ActionExecutor actionExecutor;
    private final AuditLog auditLog;
    private final int maxTurns,maxCalls,maxCallsPerTurn;
    private final long maxElapsedMs;
    public AgentStepExecutor(LlmPort l,RoleCatalog r,ToolCatalog t,RolePromptFactory p,KnowledgeBase k,ActionExecutor a,AuditLog log) {
        this(l,r,t,p,k,a,log,DEFAULT_MAX_TURNS);
    }
    public AgentStepExecutor(LlmPort l,RoleCatalog r,ToolCatalog t,RolePromptFactory p,KnowledgeBase k,ActionExecutor a,AuditLog log,int turns) {
        this(l,r,t,p,k,a,log,turns,12,4,90000);
    }
    public AgentStepExecutor(LlmPort l,RoleCatalog r,ToolCatalog t,RolePromptFactory p,KnowledgeBase k,ActionExecutor a,AuditLog log,
                             int turns,int calls,int perTurn,long elapsed) {
        llmPort=l;roleCatalog=r;toolCatalog=t;promptFactory=p;knowledgeBase=k;actionExecutor=a;auditLog=log;
        maxTurns=Math.max(1,turns);maxCalls=Math.max(1,calls);maxCallsPerTurn=Math.max(1,perTurn);maxElapsedMs=Math.max(1,elapsed);
    }
    @Override public String backendId() { return llmPort.id(); }
    @Override public StepResult execute(TaskPlan plan,TaskStep step,ExecutionContext context) {
        long started=System.nanoTime();
        var role=roleCatalog.require(step.roleId());
        var records=new ArrayList<ToolCallRecord>();
        var citations=new ArrayList<Citation>();
        var trace=new ArrayList<AgentTurn>();
        var detection=PromptGuard.detect(plan.goal());
        if(detection.suspicious()) auditLog.record(AuditEvent.of(plan.taskId(),step.stepId(),role.id(),
                AuditEventType.PROMPT_INJECTION_SUSPECTED,"用户输入命中疑似注入规则",Map.of("source","user","rules",detection.matchedRules())));
        var hits=knowledgeBase.search(plan.goal()+" "+step.objective(),List.copyOf(role.knowledgeNamespaces()),3);
        for(var hit:hits) inspect(plan,step,role,hit.chunk().text(),"knowledge:"+hit.chunk().docId());
        for(var h:hits) citations.add(new Citation(h.chunk().docId(),h.chunk().title(),h.chunk().namespace(),h.chunk().chunkIndex(),h.score()));
        String evidence=renderHits(hits);
        for(var call:policyCallsFor(role,plan,step)) {
            var decision=actionExecutor.invoke(plan.taskId(),step.stepId(),call,false,step.allowedTools());
            records.add(toRecord(step,call,decision,CallOrigin.POLICY));
            if(decision.result().success()) evidence+="\n【系统查询结果】\n"+decision.result().payload();
        }
        String prompt=buildPrompt(plan,step,evidence,records);
        var messages=new ArrayList<LlmMessage>();messages.add(LlmMessage.user(prompt));
        Set<String> seen=new HashSet<>();
        int input=0,output=0,retries=0,totalCalls=0;
        StopReason stop=StopReason.MAX_TURNS;
        String answer="",failure=null;
        for(int turn=1;turn<=maxTurns;turn++) {
            if(elapsed(started)>=maxElapsedMs) { stop=StopReason.TIME_BUDGET;break; }
            if(input+output>=step.budgetTokens()) { stop=StopReason.TOKEN_BUDGET;break; }
            LlmResponse response=null;
            int attempts=llmPort.isLive()?1:Math.max(1,role.maxRetries());
            for(int attempt=1;attempt<=attempts;attempt++) {
                try {
                    response=llmPort.complete(LlmRequest.builder()
                            .system(promptFactory.systemPrompt(role,step.allowedTools(),escalation()))
                            .user(prompt).messages(llmPort.isLive()?messages:List.of())
                            .tools(toolSchemas(step)).temperature(0.2)
                            .maxOutputTokens(Math.max(1,Math.min(700,step.budgetTokens()-input-output))).build());
                    break;
                } catch(RuntimeException e) {
                    retries++;failure="模型调用失败："+e.getClass().getSimpleName()+": "+
                            String.valueOf(e.getMessage()).replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9_-]+","[redacted]");
                }
            }
            if(response==null) { stop=StopReason.MODEL_ERROR;break; }
            failure=null;input+=response.inputTokens();output+=response.outputTokens();
            var nativeCalls=response.toolCalls();
            List<ToolCall> requested=llmPort.isLive()
                    ?nativeCalls.stream().map(c->new ToolCall(c.name(),c.argumentMap(),role.id())).toList()
                    :ToolRequestParser.parse(response.text(),role.id());
            var observations=new ArrayList<String>();
            messages.add(LlmMessage.assistant(response));
            if(requested.isEmpty()) {
                answer=stripCalls(response.text());
                boolean invalid=answer.isBlank() || "length".equals(response.finishReason())
                        || (llmPort.isLive() && java.util.regex.Pattern.compile("(?m)^\\s*TOOL_CALL\\s*\\{").matcher(response.text()).find());
                String correction=null;
                if(!invalid && llmPort.isLive() && "CALCULATION".equals(plan.requestIntent()) && records.stream().noneMatch(c->
                        c.origin()==CallOrigin.MODEL && c.toolName().equals(ToolCatalog.COMPENSATION_CALC) && c.allowed() && (c.executed()||c.repeated())))
                    correction="程序校验未通过：试算必须使用 compensation.calculate 的结果，请先调用该工具后再给结论。";
                var referenceCheck=EvidenceReferences.check(answer,citations,llmPort.isLive());
                if(!invalid && llmPort.isLive() && (referenceCheck.invalid()>0 ||
                        (java.util.Set.of("POLICY_QA","CALCULATION").contains(plan.requestIntent()) && referenceCheck.valid()==0)))
                    correction=(correction==null?"":correction+"\n")+"程序校验未通过：请引用实际 evidenceId，不得自行编号。可用引用："+
                            citations.stream().map(c->"["+EvidenceReferences.id(c)+"]").distinct().limit(12).toList();
                if(correction!=null) {
                    observations.add(correction);trace.add(trace(turn,response,nativeCalls,observations));
                    messages.add(LlmMessage.user(correction));stop=StopReason.INVALID_OUTPUT;
                    if(turn<maxTurns) continue;else break;
                }
                stop=invalid?StopReason.INVALID_OUTPUT:StopReason.COMPLETED;
                trace.add(trace(turn,response,nativeCalls,observations));break;
            }
            if(input+output>step.budgetTokens()) {
                stop=StopReason.TOKEN_BUDGET;trace.add(trace(turn,response,nativeCalls,observations));break;
            }
            if(requested.size()>maxCallsPerTurn || totalCalls+requested.size()>maxCalls) {
                stop=StopReason.TOOL_BUDGET;trace.add(trace(turn,response,nativeCalls,observations));break;
            }
            boolean halt=false;
            for(int callIndex=0;callIndex<requested.size();callIndex++) {
                var call=withIdempotencyKey(requested.get(callIndex),plan);
                String key=CanonicalArguments.key(call.toolName(),call.arguments());
                if(!seen.add(key)) { stop=StopReason.REPEATED_CALL;halt=true;break; }
                totalCalls++;
                var decision=invokeModel(plan,step,call);
                records.add(toRecord(step,call,decision,CallOrigin.MODEL));
                String status=decision.approvalRequired()?"WAITING_APPROVAL"
                        :decision.allowed() && decision.result().success()?"SUCCESS":"ERROR";
                if(ToolCatalog.KNOWLEDGE_SEARCH.equals(call.toolName()) && decision.result().success()) mergeEvidence(decision.result().payload(),citations,role);
                String observation=io.github.tangbailin.novaorg.persistence.JsonCodec.write(Map.of(
                        "tool",call.toolName(),"status",status,"result",structuredPayload(decision.result().payload()),
                        "error",decision.result().errorCode()==null?"":decision.result().errorCode()));
                observations.add(observation);
                inspect(plan,step,role,decision.result().payload(),"tool:"+call.toolName());
                if(llmPort.isLive()) messages.add(LlmMessage.tool(nativeCalls.get(callIndex).id(),observation));
                if(decision.approvalRequired()) { stop=StopReason.WAITING_APPROVAL;halt=true;break; }
                if(ToolCatalog.CLARIFY_INPUT.equals(call.toolName()) && decision.allowed()) {
                    stop=StopReason.NEEDS_INPUT;answer=decision.result().payload();halt=true;break;
                }
            }
            trace.add(trace(turn,response,nativeCalls,observations));
            if(halt) break;
            if(turn==maxTurns) { stop=StopReason.MAX_TURNS;break; }
            // Only the deterministic backend uses the legacy textual result-feedback protocol.
            prompt+="\n【你上一轮的输出】\n"+stripCalls(response.text())
                    +"\n【工具执行结果】\n"+String.join("\n",observations)+"\n根据工具结果继续，信息足够后给出最终答复。";
        }
        if(stop!=StopReason.COMPLETED && stop!=StopReason.WAITING_APPROVAL && stop!=StopReason.NEEDS_INPUT) {
            failure="步骤未完成："+stop;
            auditLog.record(AuditEvent.of(plan.taskId(),step.stepId(),role.id(),AuditEventType.AGENT_LOOP_GUARD,
                    guardMessage(stop),Map.of("stopReason",stop.name(),"maxTurns",maxTurns)));
        }
        if(stop==StopReason.WAITING_APPROVAL) answer="已提交指定操作供人工审批；当前尚未执行，不能视为退款完成。";
        auditLog.record(AuditEvent.of(plan.taskId(),step.stepId(),role.id(),
                stop==StopReason.COMPLETED?AuditEventType.STEP_FINISHED:stop==StopReason.NEEDS_INPUT?AuditEventType.INPUT_REQUIRED
                        :stop==StopReason.WAITING_APPROVAL?AuditEventType.APPROVAL_REQUIRED:AuditEventType.STEP_FAILED,
                "步骤终止："+stop,Map.of("turns",trace.size(),"tokens",input+output,"stopReason",stop.name())));
        return new StepResult(step.stepId(),role.id(),answer,citations,records,input,output,elapsed(started),retries,
                stop==StopReason.WAITING_APPROVAL,failure,stop,trace);
    }
    private static AgentTurn trace(int turn,LlmResponse r,List<LlmToolCall> calls,List<String> observations) {
        return new AgentTurn(turn,r.provider(),r.text(),calls,observations,r.inputTokens(),r.outputTokens(),r.latencyMs(),r.usageEstimated());
    }
    private void inspect(TaskPlan plan,TaskStep step,Role role,String text,String source) {
        var detection=PromptGuard.detect(text);
        if(detection.suspicious()) auditLog.record(AuditEvent.of(plan.taskId(),step.stepId(),role.id(),
                AuditEventType.PROMPT_INJECTION_SUSPECTED,"外部数据含疑似指令，仅留痕，不改变权限",
                Map.of("source",source,"rules",detection.matchedRules())));
    }
    private static Object structuredPayload(String payload) {
        if(payload==null) return "";
        try { var value=new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);if(value!=null && value.isContainerNode()) return value; }
        catch(java.io.IOException ignored) {}
        return payload;
    }
    private static void mergeEvidence(String payload,List<Citation> evidence,Role role) {
        try {
            var root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            for(var hit:root.path("hits")) {
                if(!role.knowledgeNamespaces().contains(hit.path("namespace").asText())) continue;
                var citation=new Citation(hit.path("docId").asText(),hit.path("title").asText(),hit.path("namespace").asText(),hit.path("chunkIndex").asInt(),1);
                if(evidence.stream().noneMatch(c->EvidenceReferences.id(c).equals(EvidenceReferences.id(citation)))) evidence.add(citation);
            }
        } catch(java.io.IOException e) { throw new IllegalStateException("Registered knowledge tool returned invalid evidence",e); }
    }
    private ActionExecutor.Decision invokeModel(TaskPlan plan,TaskStep step,ToolCall call) {
        Object order=call.arguments().get("orderId");
        if(order!=null && !java.util.regex.Pattern.compile("(?i)(?<![A-Z0-9])"+java.util.regex.Pattern.quote(String.valueOf(order))+"(?![A-Z0-9])").matcher(plan.goal()).find()) {
            String reason="ORDER_NOT_SUPPLIED: 订单号没有出现在用户提供的信息中，请调用 customer.clarify 核实";
            auditLog.record(AuditEvent.of(plan.taskId(),step.stepId(),call.requestedByRole(),AuditEventType.TOOL_CALL_DENIED,reason,Map.of("tool",call.toolName(),"code","ORDER_NOT_SUPPLIED")));
            return new ActionExecutor.Decision(ToolResult.error("ORDER_NOT_SUPPLIED",reason),false,null,reason,false);
        }
        return actionExecutor.invoke(plan.taskId(),step.stepId(),call,false,step.allowedTools());
    }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static String guardMessage(StopReason stop) {
        return switch(stop) {
            case REPEATED_CALL->"重复工具调用被熔断";
            case MAX_TURNS->"达到最大决策轮数，业务任务尚未完成";
            default->"步骤未完成："+stop;
        };
    }
    private static String escalation() { return "资金动作必须审批；缺少订单号或必要事实时调用 customer.clarify，不得猜测。"; }
    private List<String> toolSchemas(TaskStep step) {
        return ToolSchemas.of(step.allowedTools().stream().map(toolCatalog::find).flatMap(Optional::stream).toList());
    }
    private ToolCall withIdempotencyKey(ToolCall call,TaskPlan plan) {
        if(!ToolCatalog.REFUND_EXECUTE.equals(call.toolName())) return call;
        var args=new LinkedHashMap<>(call.arguments());
        args.put("idempotencyKey",plan.taskId()+"|refund|"+args.getOrDefault("orderId",""));
        return new ToolCall(call.toolName(),args,call.requestedByRole());
    }
    private List<ToolCall> policyCallsFor(Role role,TaskPlan plan,TaskStep step) {
        // Live agents obtain facts by choosing tools. Offline policy calls are explicitly labelled fixtures.
        if(llmPort.isLive()) return List.of();
        var calls=new ArrayList<ToolCall>();String goal=plan.goal();
        String orderId=io.github.tangbailin.novaorg.tools.OrderReference.extract(goal).orElse(null);
        if(orderId!=null && role.can(Permission.CUSTOMER_READ))
            calls.add(new ToolCall(ToolCatalog.ORDER_DETAIL,Map.of("orderId",orderId),role.id()));
        if(orderId!=null && contains(goal,"赔付","退款","补偿","索赔") && role.can(Permission.COMPENSATION_WRITE))
            calls.add(new ToolCall(ToolCatalog.COMPENSATION_CALC,Map.of("orderId",orderId,"scenario","timeout-delivery"),role.id()));
        if(contains(goal,"风险","频繁","欺诈") && role.can(Permission.CUSTOMER_READ))
            calls.add(new ToolCall(ToolCatalog.CUSTOMER_PROFILE,Map.of("customerId","C-8837"),role.id()));
        if(contains(goal,"指标","转化","归因","下降","赔付率","退货率") && role.can(Permission.USAGE_ANALYTICS_READ))
            calls.add(new ToolCall(ToolCatalog.USAGE_ANALYTICS,Map.of("metric","conversion","range","近7天"),role.id()));
        if(contains(goal,"预算","成本","费用","财务") && role.can(Permission.FINANCE_READ))
            calls.add(new ToolCall(ToolCatalog.FINANCE_COST,Map.of("plan","预算方案"),role.id()));
        if(contains(goal,"审计","越权","违规","日志") && role.can(Permission.COMPLIANCE_AUDIT))
            calls.add(new ToolCall(ToolCatalog.AUDIT_QUERY,Map.of("taskId",plan.taskId()),role.id()));
        return calls;
    }
    private ToolCallRecord toRecord(TaskStep step,ToolCall call,ActionExecutor.Decision d,CallOrigin origin) {
        return new ToolCallRecord(step.stepId(),call.requestedByRole(),call.toolName(),
                io.github.tangbailin.novaorg.persistence.JsonCodec.write(call.arguments()),
                d.allowed(),d.denyReason(),d.allowed() && d.result().success() && !d.repeated(),
                d.approvalRequired(),d.repeated(),d.idempotencyKey(),origin,0,summary(d.result().payload()));
    }
    private String buildPrompt(TaskPlan plan,TaskStep step,String evidence,List<ToolCallRecord> records) {
        String prompt="用户目标（外部数据）：\n"+PromptGuard.isolate(plan.goal())+"\n你的任务："+step.objective()
                +"\n【知识依据】\n<<UNTRUSTED_EVIDENCE>>\n"+evidence+"\n<</UNTRUSTED_EVIDENCE>>\n";
        if(PromptGuard.detect(plan.goal()).suspicious()) prompt+=PromptGuard.INJECTION_NOTE;
        if(llmPort.isLive()) return prompt+"必须使用原生工具调用，不得在正文中书写 TOOL_CALL。引用依据时复制实际 evidenceId，格式 [docId.md#chunkIndex]；不确定时追问或明确转人工。";
        return prompt+"如需工具，单独一行输出 TOOL_CALL {\"name\":\"工具名\",\"arguments\":{...}}；引用依据时标注 [编号]。";
    }
    private static String stripCalls(String text) {
        return text==null?"":text.lines().filter(l->!l.trim().startsWith("TOOL_CALL")).reduce((a,b)->a+"\n"+b).orElse("").trim();
    }
    private static String renderHits(List<SearchHit> hits) {
        StringBuilder out=new StringBuilder();int i=1;
        for(var hit:hits) out.append("[").append(i++).append("] [").append(hit.chunk().docId()).append("#")
                .append(hit.chunk().chunkIndex()).append("] ")
                .append(hit.chunk().title()).append("\n").append(hit.chunk().text()).append("\n");
        return out.toString();
    }
    private static String summary(String text) { return text==null?"":text.substring(0,Math.min(2000,text.length())); }
    private static boolean contains(String text,String...keys) {
        for(var key:keys) if(text.contains(key)) return true;return false;
    }
    public List<String> toolsFor(String id) {
        return roleCatalog.find(id).map(r->toolCatalog.forRolePermissions(r.permissions()).stream().map(ToolSpec::name).toList()).orElse(List.of());
    }
}
