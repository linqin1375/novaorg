package io.github.tangbailin.novaorg.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.audit.*;
import io.github.tangbailin.novaorg.domain.*;
import io.github.tangbailin.novaorg.knowledge.*;
import io.github.tangbailin.novaorg.llm.*;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.*;
import io.github.tangbailin.novaorg.runtime.*;
import io.github.tangbailin.novaorg.tools.BusinessSystems;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Offline regression and live trials share graders, never production task/ledger state. */
@Component
public class EvalRunner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ToolCatalog tools;
    private final KnowledgeBase knowledge;
    private final LlmPort llm;
    private final CostModel costs;
    private EvalReport latest;
    public EvalRunner(WorkflowEngine unused,ToolCatalog tools,KnowledgeBase knowledge,LlmPort llm,CostModel costs) {
        this.tools=tools;this.knowledge=knowledge;this.llm=llm;this.costs=costs;
    }
    private record Fixture(WorkflowEngine engine,InMemoryAuditLog audit,BusinessSystems business) {}
    private Fixture fixture() {
        var roles=new RoleCatalog();var audit=new InMemoryAuditLog();var business=new BusinessSystems();
        var actions=new io.github.tangbailin.novaorg.policy.DefaultActionExecutor(tools,roles,
                new io.github.tangbailin.novaorg.policy.RoleGuard(tools,roles,audit),knowledge,business,audit);
        var executor=new AgentStepExecutor(llm,roles,tools,new RolePromptFactory(tools),knowledge,actions,audit);
        var planner=new io.github.tangbailin.novaorg.plan.TaskPlanner(llm,roles,tools,audit,costs,4,8000);
        return new Fixture(new WorkflowEngine(planner,executor,roles,tools,audit,costs,new ReportComposer(roles),actions),audit,business);
    }
    public List<EvalCase> loadCases(String location) {
        try(var in=new org.springframework.core.io.ClassPathResource(location).getInputStream()) {
            return JSON.readValue(in,new TypeReference<List<EvalCase>>() {});
        } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    public EvalReport run(List<EvalCase> cases) {
        var results=new ArrayList<EvalResult>();var grader=new Grader();int seq=0;
        for(var c:cases) {
            var f=fixture();long start=System.nanoTime();RunTrace trace;
            try {
                var result=f.engine().execute("eval-"+(++seq)+"-"+c.id(),c.goal(),"gate");
                trace=traceOf(result,f.audit(),f.business().refundCount(),(System.nanoTime()-start)/1_000_000);
            } catch(RuntimeException e) {
                trace=new RunTrace("FAILED",List.of(),List.of(),List.of(),List.of(),List.of(),false,
                        (System.nanoTime()-start)/1_000_000,0,0,false,List.of(),List.of(),List.of(),0,0,!llm.isLive(),List.of(),e.getClass().getSimpleName()+": "+e.getMessage(),false,List.of());
            }
            results.add(grader.grade(c,trace));
        }
        latest=aggregate(results);return latest;
    }
    public EvalReport latest() {
        if(latest!=null) return latest;
        Path p=Path.of("reports","eval-latest.json");
        if(!Files.exists(p)) return null;
        try { return JSON.readValue(Files.readString(p),EvalReport.class); }
        catch(java.io.IOException e) { return null; }
    }
    public RunTrace traceOf(WorkflowResult result) { return traceOf(result,null,0,result.run().totalLatencyMs()); }
    public RunTrace traceOf(WorkflowResult result,AuditLog audit) { return traceOf(result,audit,0,result.run().totalLatencyMs()); }
    private RunTrace traceOf(WorkflowResult result,AuditLog audit,int refunds,long elapsed) {
        var run=result.run();
        var roles=run.steps().stream().map(TaskStep::roleId).toList();
        var called=new ArrayList<String>();var executed=new ArrayList<String>();var denied=new ArrayList<String>();
        var docs=new ArrayList<String>();var modelCalls=new ArrayList<String>();var modelExecuted=new ArrayList<String>();
        var answerDocs=new ArrayList<String>();boolean violation=false,estimated=!llm.isLive();int invalid=0,input=0,output=0;
        for(var step:run.results().values()) {
            input+=step.inputTokens();output+=step.outputTokens();step.citations().forEach(c->docs.add(c.docId()));
            var checked=EvidenceReferences.check(step.output(),step.citations(),llm.isLive());
            answerDocs.addAll(checked.docs());invalid+=checked.invalid();
            for(var t:step.trace()) estimated|=t.usageEstimated();
            for(var c:step.toolCalls()) {
                called.add(c.toolName());
                if(c.origin()==CallOrigin.MODEL) modelCalls.add(c.toolName());
                if(c.allowed() && (c.executed() || c.repeated())) {
                    executed.add(c.toolName());
                    if(c.origin()==CallOrigin.MODEL) modelExecuted.add(c.toolName());
                    var spec=tools.find(c.toolName()).orElse(null);
                    if(c.executed() && spec!=null && spec.risk().requiresApproval() && run.approvedActions().stream().noneMatch(a->
                            a.stepId().equals(c.stepId()) && a.call().toolName().equals(c.toolName()) &&
                            CanonicalArguments.key(c.toolName(),a.call().arguments()).equals(
                                CanonicalArguments.key(c.toolName(),io.github.tangbailin.novaorg.persistence.JsonCodec.read(c.arguments(),Map.class)))))
                        violation=true;
                } else if(!c.allowed()) denied.add(c.toolName());
            }
        }
        String status=run.failureReason()!=null?"FAILED":run.awaitingInput()?"NEEDS_INPUT":
                result.waitingApproval()?"WAITING_APPROVAL":run.allStepsDone()?"DONE":"RUNNING";
        boolean injection=audit!=null && audit.byTask(run.taskId()).stream().anyMatch(e->e.type()==AuditEventType.PROMPT_INJECTION_SUSPECTED
                && (e.attributes().get("source")==null || "user".equals(e.attributes().get("source"))));
        return new RunTrace(status,roles,called,executed,denied,docs,violation,elapsed,input+output,costs.cost(input,output),
                injection,modelCalls,modelExecuted,answerDocs,invalid,refunds,estimated,List.copyOf(run.results().values()),run.failureReason(),run.slaMet(),
                run.pendingActions().stream().filter(a->ToolCatalog.REFUND_EXECUTE.equals(a.call().toolName()))
                        .map(a->new java.math.BigDecimal(String.valueOf(a.call().arguments().get("amount"))).movePointRight(2).longValueExact()).toList());
    }
    private EvalReport aggregate(List<EvalResult> rs) {
        int n=rs.size(),passed=0,routes=0,coverage=0,hit=0,violations=0,injExpected=0,injDetected=0,fp=0,tokens=0;
        int requests=0,success=0,answerHits=0,done=0;long elapsed=0;double cost=0;boolean estimated=!llm.isLive();
        for(var r:rs) {
            if(r.passed()) passed++;if(r.actualRoles().containsAll(r.expectRoles())) routes++;
            if(r.actualTools().containsAll(r.expectTools())) coverage++;if(r.citationHit()) hit++;
            if(r.permissionViolation()) violations++;if(r.injectionExpected()) {injExpected++;if(r.injectionDetected()) injDetected++;}
            else if(r.injectionDetected()) fp++;
            elapsed+=r.latencyMs();tokens+=r.tokens();cost+=r.costUsd();requests+=r.modelToolRequests();success+=r.successfulModelTools();
            if(r.answerCitationHit()) answerHits++;if(r.completed()) done++;estimated|=r.usageEstimated();
        }
        return new EvalReport(Instant.now().toString(),llm.id(),knowledge.embeddingId(),n,passed,ratio(passed,n),
                ratio(routes,n),ratio(coverage,n),ratio(hit,n),violations,n==0?0:elapsed/n,n==0?0:tokens/n,cost,
                injExpected,injDetected,ratio(injDetected,injExpected),fp,ratio(fp,n-injExpected),List.copyOf(rs),
                requests,success,ratio(success,requests),ratio(answerHits,n),ratio(done,n),estimated,
                Map.of("protocolVersion","2-native-messages","costMeaning","configured-rate estimate, not invoice",
                        "answerSupport","reference existence checked; semantic entailment requires review",
                        "dataOrigin","synthetic cases; original complaint URLs and human annotation not independently verified"));
    }
    private static double ratio(int a,int b) { return b==0?0:Math.round(a*10000.0/b)/10000.0; }
    public void write(EvalReport report,Path json,Path markdown) throws java.io.IOException {
        Files.createDirectories(json.toAbsolutePath().getParent());Files.createDirectories(markdown.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(json.toFile(),report);
        Files.writeString(markdown,markdown(report),StandardCharsets.UTF_8);
    }
    public String markdown(EvalReport r) {
        var s=new StringBuilder("# NovaOrg 评测报告\n\n");
        s.append("- 后端：").append(r.llm()).append("\n- 检索：").append(r.embedding())
                .append("\n- 用例：").append(r.passed()).append("/").append(r.total())
                .append("\n- usage 含估算：").append(r.usageEstimated()).append("\n\n");
        s.append("| 指标 | 数值 | 含义 |\n|---|---|---|\n")
                .append("| 派单覆盖率 | ").append(r.routingAccuracy()).append(" | 预期角色子集 |\n")
                .append("| 预期工具请求覆盖率 | ").append(r.toolCompliance()).append(" | 含离线平台取证，不代表执行成功 |\n")
                .append("| 模型工具成功/请求 | ").append(r.successfulModelTools()).append("/").append(r.modelToolRequests()).append(" | 审批等待也属于尚未执行 |\n")
                .append("| 检索命中率 | ").append(r.citationHitRate()).append(" | 检索候选是否有预期文档 |\n")
                .append("| 回答引用命中率 | ").append(r.answerCitationHitRate()).append(" | 答复实际引用编号对应文档；不等于语义支持 |\n")
                .append("| 任务完成率 | ").append(r.taskCompletionRate()).append(" | DONE；正确暂停不等于失败 |\n")
                .append("| 高风险越权执行 | ").append(r.permissionViolations()).append(" | 未审批执行的次数 |\n")
                .append("| 注入检出 | ").append(r.injectionDetected()).append("/").append(r.injectionExpected()).append(" | 当前样例 |\n")
                .append("| 正常输入误报率 | ").append(r.injectionFalsePositiveRate()).append(" | 只留痕也仍是误报 |\n")
                .append("| 费用估算 | $").append(r.totalCostUsd()).append(" | 配置费率折算，不是账单 |\n\n");
        s.append("| 用例 | 通过 | 结束状态 | 退款流水 | 原因 |\n|---|---|---|---|---|\n");
        for(var c:r.cases()) s.append("| ").append(c.caseId()).append(" | ").append(c.passed()).append(" | ").append(c.status())
                .append(" | ").append(c.refundCount()).append(" | ").append(String.join("；",c.failures()).replace("|","/")).append(" |\n");
        s.append("\n规则后端只验证软件流程。真实模型质量需要 live 报告；逐轮轨迹在 JSON 的 trajectory 中。\n")
                .append("语义引用支持仍需人工复核；编号存在、命中文档和结论正确是三个不同指标。\n");
        return s.toString();
    }
}
