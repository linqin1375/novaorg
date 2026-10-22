package io.github.tangbailin.novaorg.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.audit.InMemoryAuditLog;
import io.github.tangbailin.novaorg.eval.EvalReport;
import io.github.tangbailin.novaorg.eval.EvalRunner;
import io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.HashingEmbeddingModel;
import io.github.tangbailin.novaorg.llm.DemoLlmPort;
import io.github.tangbailin.novaorg.llm.RolePromptFactory;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.plan.TaskPlanner;
import io.github.tangbailin.novaorg.policy.DefaultActionExecutor;
import io.github.tangbailin.novaorg.policy.RoleGuard;
import io.github.tangbailin.novaorg.runtime.AgentStepExecutor;
import io.github.tangbailin.novaorg.runtime.ReportComposer;
import io.github.tangbailin.novaorg.runtime.WorkflowEngine;
import io.github.tangbailin.novaorg.tools.BusinessSystems;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the console API. They run the real (demo) backend through the real
 * pipeline, so a change that breaks the permission model breaks these tests too.
 */
class ApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;

    private void identity(String name) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name,"unused",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @BeforeEach
    void setUp() {
        identity("alice");
        RoleCatalog roles = new RoleCatalog();
        ToolCatalog tools = new ToolCatalog();
        var auditLog = new InMemoryAuditLog();
        var knowledge = new ClasspathKnowledgeBase(new HashingEmbeddingModel());
        var guard = new RoleGuard(tools, roles, auditLog);
        var actionExecutor = new DefaultActionExecutor(tools, roles, guard, knowledge,
                new BusinessSystems(), auditLog);
        var executor = new AgentStepExecutor(new DemoLlmPort(), roles, tools,
                new RolePromptFactory(tools), knowledge, actionExecutor, auditLog);
        var planner = new TaskPlanner(new DemoLlmPort(), roles, tools, auditLog,
                CostModel.defaultModel(), 4, 8000);
        var costModel = CostModel.defaultModel();
        var reportComposer = new ReportComposer(roles);
        var engine = new WorkflowEngine(planner, executor, roles, tools, auditLog, costModel,
                reportComposer, actionExecutor);
        var mapper = new ViewMapper(roles, tools, costModel, knowledge, new DemoLlmPort());
        var properties = new io.github.tangbailin.novaorg.config.NovaOrgProperties(
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Llm("demo", "", "", "", 1000, 1),
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Embedding(
                        "local", "", "", "", 30000, 1024, 60, 20, 0.6),
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Planner(4, 1500),
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Cost(0.3, 1.2),
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Demo(false, false, false),
                new io.github.tangbailin.novaorg.config.NovaOrgProperties.Approval(true, "approver"));
        var service = new TaskRunService(engine, auditLog, mapper, reportComposer, properties);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TaskController(service),
                        new OrgController(roles, tools, engine, service, mapper),
                        new AuditExportController(service))
                .build();
    }

    @Test
    void orgChartExposesRolesWithTheirPermissionSets() throws Exception {
        mockMvc.perform(get("/api/org/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[?(@.id=='compensation_specialist')].permissions").exists())
                .andExpect(jsonPath("$[?(@.id=='customer_service_agent')]").isNotEmpty());
    }

    @Test
    void toolRegistryExposesRiskAndPermissionForEveryTool() throws Exception {
        mockMvc.perform(get("/api/org/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='refund.execute')].risk").value("HIGH"))
                .andExpect(jsonPath("$[?(@.name=='knowledge.search')].requiredPermission")
                        .value("knowledge:read"));
    }

    @Test
    void submittingATaskReturnsAStaffedPlanWithPerStepEvidence() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"客户投诉：订单 A100 超时 72 小时未发货，要求赔付","requestedBy":"test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.metrics.totalTokens").isNumber())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("steps").size()).isGreaterThanOrEqualTo(2);
        assertThat(body.path("steps").get(0).path("roleName").asText()).isNotBlank();
        assertThat(body.path("report").asText()).contains("交付报告");

        // the refund step must be visible as a refused, approval-gated call - not as a success
        boolean blockedRefund = false;
        for (JsonNode step : body.path("steps")) {
            for (JsonNode call : step.path("toolCalls")) {
                if ("refund.execute".equals(call.path("toolName").asText())) {
                    blockedRefund = true;
                    assertThat(call.path("allowed").asBoolean()).isFalse();
                    assertThat(call.path("approvalRequired").asBoolean()).isTrue();
                    assertThat(call.path("denyReason").asText()).contains("APPROVAL_REQUIRED");
                }
            }
        }
        assertThat(blockedRefund)
                .withFailMessage("赔付专员应尝试调用 refund.execute 并被权限系统拦截")
                .isTrue();
    }

    @Test
    void approvalGatePausesTheTaskAndCanBeReleasedOverHttp() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"客户投诉：订单 A100 超时未发货，要求立即退款","approvalMode":"gate"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andReturn();

        String taskId = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("taskId").asText();

        identity("bob");
        mockMvc.perform(post("/api/tasks/" + taskId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted":true,"approver":"spoofed","revision":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void auditTrailIsQueryablePerTask() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"客户投诉：订单 A100 超时 72 小时未发货，要求赔付"}
                                """))
                .andReturn();
        String taskId = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("taskId").asText();

        mockMvc.perform(get("/api/tasks/" + taskId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").exists())
                .andExpect(jsonPath("$[0].traceId").exists())
                .andExpect(jsonPath("$[?(@.type=='TASK_PLANNED')]").isNotEmpty());
    }

    /**
     * Four-eyes principle: the person releasing a high-risk action must not be the person who
     * asked for it. Enforced in the runtime, surfaced as 409 (not 400 - the payload is fine,
     * the requester has to change).
     */
    @Test
    void theRequesterCannotApproveTheirOwnHighRiskAction() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"客户投诉：订单 A100 超时未发货，要求立即退款",
                                 "requestedBy":"alice","approvalMode":"gate"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andReturn();
        String taskId = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("taskId").asText();

        mockMvc.perform(post("/api/tasks/" + taskId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted":true,"approver":"alice","revision":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEPARATION_OF_DUTIES"));

        // a different authenticated person can release it
        identity("bob");
        mockMvc.perform(post("/api/tasks/" + taskId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"granted":true,"approver":"spoofed","revision":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.approvedBy").value("bob"));
    }

    @Test
    void unknownTaskReturns404WithAMachineReadableCode() throws Exception {
        mockMvc.perform(get("/api/tasks/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    /**
     * Regression: the delivery report used to be produced only on the submit response, so
     * reloading a task from the history list returned an empty report and the console dropped
     * the card. A read view must carry exactly what the write view carried.
     */
    @Test
    void reloadingATaskReturnsTheSameReportAndApprovalStateAsTheSubmitResponse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"客户投诉：订单 A100 超时未发货，要求立即退款","approvalMode":"gate"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode onCreate = MAPPER.readTree(created.getResponse().getContentAsString());
        String taskId = onCreate.path("taskId").asText();
        String reportOnCreate = onCreate.path("report").asText();
        assertThat(reportOnCreate).isNotBlank();

        MvcResult reloaded = mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode onReload = MAPPER.readTree(reloaded.getResponse().getContentAsString());

        assertThat(onReload.path("report").asText()).isEqualTo(reportOnCreate);
        assertThat(onReload.path("status").asText()).isEqualTo(onCreate.path("status").asText());
        assertThat(onReload.path("pendingApprovalReason").asText())
                .isEqualTo(onCreate.path("pendingApprovalReason").asText())
                .isNotBlank();

        // the history list must be equally complete
        MvcResult list = mockMvc.perform(get("/api/tasks")).andExpect(status().isOk()).andReturn();
        JsonNode listed = MAPPER.readTree(list.getResponse().getContentAsString());
        JsonNode match = null;
        for (JsonNode node : listed) {
            if (taskId.equals(node.path("taskId").asText())) {
                match = node;
            }
        }
        assertThat(match).withFailMessage("任务未出现在列表里：%s", taskId).isNotNull();
        assertThat(match.path("report").asText()).isEqualTo(reportOnCreate);
    }

    @Test
    void metricsAreAggregatedPerRole() throws Exception {        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"goal":"客户投诉：订单 A100 超时 72 小时未发货，要求赔付"}
                        """));

        mockMvc.perform(get("/api/metrics/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleId").exists())
                .andExpect(jsonPath("$[0].costUsd").isNumber());
    }

    @Test
    void demoModeStartsWithoutCredentialsAndSaysWhichBackendRan() throws Exception {
        mockMvc.perform(get("/api/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveModel").value(false))
                .andExpect(jsonPath("$.llmBackend").value("demo-deterministic-rules"));
    }

    /**
     * Status semantics, locked down because getting them wrong misleads the reader:
     * <ul>
     *   <li>a paused workflow reports WAITING_APPROVAL;</li>
     *   <li>a finished workflow that merely refused a high-risk action reports DONE plus a
     *       blockingReason - never "waiting for approval", because nothing is waiting.</li>
     * </ul>
     */
    @Test
    void legacyAutoModeCannotBypassApproval() throws Exception {
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\":\"订单 A100 客户要求立即退款\",\"approvalMode\":\"auto\",\"requestedBy\":\"spoofed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.requestedBy").value("alice"))
                .andExpect(jsonPath("$.pendingActions[0].call.arguments.orderId").value("A100"));
    }

    /** An audit trail you cannot hand to an auditor is not an audit trail. */
    @Test
    void auditTrailCanBeExportedAndTheExportIsItselfAudited() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"goal":"客户投诉：订单 A100 超时 72 小时未发货，要求赔付"}
                        """));

        // CSV: BOM + header row + at least one record
        MvcResult csv = mockMvc.perform(get("/api/audit/export").param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn();
        String csvBody = csv.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csvBody).startsWith("\uFEFFat,traceId,taskId,stepId,roleId,type,summary,attributes");
        assertThat(csvBody.lines().count()).isGreaterThan(2);
        assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("attachment");

        // JSON must be parseable by a real parser
        MvcResult json = mockMvc.perform(get("/api/audit/export").param("format", "json"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode parsed = MAPPER.readTree(
                json.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed.size()).isGreaterThan(0);
        assertThat(parsed.get(0).path("type").asText()).isNotBlank();

        // markdown export renders a table
        MvcResult md = mockMvc.perform(get("/api/audit/export").param("format", "md"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(md.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("# 审计日志导出")
                .contains("| 时间 | traceId |");

        // and the export itself left a trace - who exported what, in which format
        MvcResult after = mockMvc.perform(get("/api/tasks/audit").param("type", "AUDIT_EXPORTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("AUDIT_EXPORTED"))
                .andReturn();
        JsonNode exportEvents = MAPPER.readTree(
                after.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(exportEvents.size()).isEqualTo(3);
        assertThat(exportEvents.get(0).path("attributes").path("format").asText()).isNotBlank();
    }

    /** The evaluation suite is itself part of the contract: it must pass, with zero violations. */
    @Test
    void theEvaluationSuitePassesWithZeroPermissionViolations() throws IOException {
        RoleCatalog roles = new RoleCatalog();
        ToolCatalog tools = new ToolCatalog();
        var auditLog = new InMemoryAuditLog();
        var knowledge = new ClasspathKnowledgeBase(new HashingEmbeddingModel());
        var guard = new RoleGuard(tools, roles, auditLog);
        var actionExecutor = new DefaultActionExecutor(tools, roles, guard, knowledge,
                new BusinessSystems(), auditLog);
        var executor = new AgentStepExecutor(new DemoLlmPort(), roles, tools,
                new RolePromptFactory(tools), knowledge, actionExecutor, auditLog);
        var planner = new TaskPlanner(new DemoLlmPort(), roles, tools, auditLog,
                CostModel.defaultModel(), 4, 1500);
        var engine = new WorkflowEngine(planner, executor, roles, tools, auditLog,
                CostModel.defaultModel(), new ReportComposer(roles), actionExecutor);
        var runner = new EvalRunner(engine, tools, knowledge, new DemoLlmPort(), CostModel.defaultModel());

        EvalReport report = runner.run(runner.loadCases("eval/cases.json"));

        assertThat(report.total()).isGreaterThanOrEqualTo(40);
        assertThat(report.permissionViolations()).isZero();
        assertThat(report.passed())
                .withFailMessage("失败的用例：%s", report.cases().stream()
                        .filter(c -> !c.passed())
                        .map(c -> c.caseId() + " -> " + c.failures())
                        .toList())
                .isEqualTo(report.total());
        // a case can pass every behavioural check while every step silently failed, so the
        // suite also asserts that work actually happened
        assertThat(report.cases())
                .withFailMessage("有用例没有产生任何 token，说明步骤实际执行失败：%s",
                        report.cases().stream().filter(c -> c.tokens() == 0)
                                .map(c -> c.caseId()).toList())
                .allMatch(c -> c.tokens() > 0);
    }
}
