package io.github.tangbailin.novaorg.api;

import com.fasterxml.jackson.databind.*;
import io.github.tangbailin.novaorg.persistence.*;
import io.github.tangbailin.novaorg.runtime.*;
import io.github.tangbailin.novaorg.policy.*;
import io.github.tangbailin.novaorg.domain.*;
import io.github.tangbailin.novaorg.org.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:security-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "NOVAORG_OPERATOR_PASSWORD=test-operator-1234","NOVAORG_REVIEWER_PASSWORD=test-reviewer-1234","NOVAORG_ADMIN_PASSWORD=test-admin-1234"})
@AutoConfigureMockMvc
class DurableSecurityIntegrationTest {
    @Test void missingOrderCanBeClarifiedOnlyByRequesterAndResumesTheCorrectOrder() throws Exception {
        var pending=mapper.readTree(mvc.perform(post("/api/tasks").with(user("alice").roles("OPERATOR")).with(csrf())
                .contentType("application/json").content("{\"goal\":\"请帮我办理全额退款，我还没提供订单号\"}"))
                .andExpect(jsonPath("$.status").value("NEEDS_INPUT")).andReturn().getResponse().getContentAsString());
        String url="/api/tasks/"+pending.path("taskId").asText()+"/clarify";
        String body="{\"answer\":\"订单 A101\",\"revision\":"+pending.path("revision").asLong()+"}";
        mvc.perform(post(url).with(user("bob").roles("OPERATOR")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("alice").roles("OPERATOR")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.pendingActions[0].call.arguments.orderId").value("A101"))
                .andExpect(jsonPath("$.pendingActions[0].call.arguments.amount").value(259.0));
        mvc.perform(post(url).with(user("alice").roles("OPERATOR")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isZero();
    }
    @Test void aDifferentOrderUsesItsOwnDatabaseBalance() {
        var ledger=new RefundLedger(jdbc,new TransactionTemplate(manager));
        assertThat(ledger.execute("separate-order","A101",25900)).isTrue();
        assertThat(ledger.findOrder("A101").orElseThrow().refundedFen()).isEqualTo(25900);
        assertThat(ledger.findOrder("A100").orElseThrow().refundedFen()).isZero();
        assertThatThrownBy(()->ledger.execute("overflow","A101",1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkflowEngine engine;
    @Autowired DefaultActionExecutor actions;
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM audit_events");jdbc.update("DELETE FROM task_runs");jdbc.update("DELETE FROM refund_receipts");jdbc.update("UPDATE simulated_orders SET refunded_fen=0");
    }
    private JsonNode create(String user) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/tasks").with(user(user).roles("OPERATOR")).with(csrf())
                .contentType("application/json").content("{\"goal\":\"客户要求立即退款订单 A100\",\"requestedBy\":\"forged\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String body(JsonNode task,boolean granted) {return "{\"granted\":"+granted+",\"approver\":\"forged\",\"revision\":"+task.path("revision").asLong()+",\"reason\":\"已核对订单及金额\"}";}
    @Test void anonymousRequestsAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tasks").with(user("operator")).contentType("application/json").content("{\"goal\":\"hello\"}")).andExpect(status().isForbidden());
    }
    @Test void realPasswordAuthenticationWorksAndBadPasswordsFail() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).param("username","operator").param("password","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").with(csrf()).param("username","operator").param("password","test-operator-1234")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT password FROM users WHERE username='operator'",String.class)).startsWith("$2").doesNotContain("test-operator");
    }
    @Test void requesterComesFromSessionAndOperatorCannotApprove() throws Exception {
        var task=create("operator");assertThat(task.path("requestedBy").asText()).isEqualTo("operator");
        mvc.perform(post("/api/tasks/"+task.path("taskId").asText()+"/approve").with(user("operator").roles("OPERATOR")).with(csrf()).contentType("application/json").content(body(task,true))).andExpect(status().isForbidden());
    }
    @Test void taskAndAuditCannotBeReadByAnotherOperator() throws Exception {
        var task=create("alice");String id=task.path("taskId").asText();
        mvc.perform(get("/api/tasks/"+id).with(user("bob").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/"+id+"/audit").with(user("bob").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks").with(user("bob").roles("OPERATOR"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/audit/export").with(user("bob").roles("OPERATOR"))).andExpect(status().isForbidden());
    }
    @Test void selfApprovalIsRejectedEvenWhenPayloadNamesAnotherApprover() throws Exception {
        var task=create("reviewer");
        mvc.perform(post("/api/tasks/"+task.path("taskId").asText()+"/approve").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content(body(task,true))).andExpect(status().isConflict());
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isZero();
    }
    @Test void exactSnapshotIsApprovedOnceAndAuditRecordsAuthenticatedActor() throws Exception {
        var task=create("operator");String id=task.path("taskId").asText();
        assertThat(task.path("pendingActions").get(0).path("call").path("arguments").path("amount").asDouble()).isEqualTo(129.0);
        mvc.perform(post("/api/tasks/"+id+"/approve").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content(body(task,true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.approvedBy").value("reviewer"));
        mvc.perform(post("/api/tasks/"+id+"/approve").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content(body(task,true))).andExpect(status().isConflict());
        var ledger=new RefundLedger(jdbc,new TransactionTemplate(manager));assertThat(ledger.count()).isEqualTo(1);assertThat(ledger.total()).isEqualTo(12900);
        assertThat(new JdbcAuditLog(jdbc).byTask(id)).anyMatch(e->e.type()==io.github.tangbailin.novaorg.audit.AuditEventType.APPROVAL_GRANTED && "reviewer".equals(e.attributes().get("actor")));
    }
    @Test void missingOrStaleRevisionCannotExecuteAnything() throws Exception {
        var task=create("operator");String url="/api/tasks/"+task.path("taskId").asText()+"/approve";
        mvc.perform(post(url).with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content("{\"granted\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content("{\"granted\":true,\"revision\":0}")).andExpect(status().isConflict());
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isZero();
    }
    @Test void rejectedTasksRemainTerminalOnResume() throws Exception {
        var task=create("operator");String id=task.path("taskId").asText();
        mvc.perform(post("/api/tasks/"+id+"/approve").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content(body(task,false))).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/tasks/"+id+"/resume").with(user("operator").roles("OPERATOR")).with(csrf())).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isZero();
    }
    @Test void aNewStoreRestoresPendingActionsAndCompletedResults() throws Exception {
        var task=create("operator");String id=task.path("taskId").asText();
        var restored=new JdbcTaskStore(jdbc,new TransactionTemplate(manager)).get(id);
        assertThat(restored.awaitingApproval()).isTrue();assertThat(restored.pendingActions()).hasSize(1);
        assertThat(restored.steps()).hasSize(2);assertThat(restored.results()).hasSize(2);
        engine.approve(id,true,"reviewer",restored.revision(),"恢复后审批");
        var completed=new JdbcTaskStore(jdbc,new TransactionTemplate(manager)).get(id);
        assertThat(completed.awaitingApproval()).isFalse();assertThat(completed.approvedActions()).hasSize(1);
        assertThat(completed.results().values()).anyMatch(r->r.toolCalls().stream().anyMatch(c->c.toolName().equals("refund.execute")&&c.executed()));
    }
    @Test void concurrentApprovalsAcceptExactlyOneRevision() throws Exception {
        var task=create("operator");String id=task.path("taskId").asText();long rev=task.path("revision").asLong();
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var accepted=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<16;i++) futures.add(pool.submit(()->{start.await();try{engine.approve(id,true,"reviewer",rev,"concurrent");accepted.incrementAndGet();}catch(IllegalStateException expected){}return null;}));
            start.countDown();for(var future:futures) future.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(accepted.get()).isEqualTo(1);assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isEqualTo(1);
    }
    @Test void fourHundredDatabaseRetriesAcrossIndependentLedgerObjectsMoveMoneyOnce() throws Exception {
        var tx=new TransactionTemplate(manager);var first=new RefundLedger(jdbc,tx);var second=new RefundLedger(jdbc,tx);
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var moved=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++){final int n=i;futures.add(pool.submit(()->{start.await();for(int j=0;j<50;j++)if((n%2==0?first:second).execute("stable-key","A100",12900))moved.incrementAndGet();return null;}));}
            start.countDown();for(var f:futures)f.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(moved.get()).isEqualTo(1);assertThat(second.total()).isEqualTo(12900);
        assertThat(new RefundLedger(jdbc,tx).execute("stable-key","A100",12900)).isFalse();
        assertThatThrownBy(()->second.execute("stable-key","A100",12800)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->second.execute("different-key","A100",1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REFUND_LIMIT");
    }
    @Test void receiptAndBalanceRollbackTogether() {
        var tx=new TransactionTemplate(manager);var ledger=new RefundLedger(jdbc,tx);
        assertThatThrownBy(()->tx.execute(s->{ledger.execute("rollback","A100",100);throw new IllegalStateException("fault injected after debit");})).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.count()).isZero();assertThat(jdbc.queryForObject("SELECT refunded_fen FROM simulated_orders WHERE order_id='A100'",Long.class)).isZero();
    }
    @Test void repeatedUnauthorizedCallsNeverBecomeSuccessfulAndActualStepScopeIsEnforced() {
        var call=new ToolCall("refund.execute",Map.of("orderId","A100","amount",129,"reason","test","idempotencyKey","user-key"),RoleCatalog.COMPENSATION);
        assertThat(actions.invoke("t-x","s-1",call,false,List.of("refund.execute")).approvalRequired()).isTrue();
        assertThat(actions.invoke("t-x","s-1",call,false,List.of("refund.execute")).allowed()).isFalse();
        assertThat(actions.invoke("t-x","s-1",call,true,List.of("order.detail")).allowed()).isFalse();
        assertThat(actions.invoke("t-x","s-1",call,true,List.of("refund.execute")).allowed()).isTrue();
        assertThat(actions.invoke("t-x","s-2",call,true,List.of("refund.execute")).repeated()).isTrue();
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isEqualTo(1);
    }
    @Test void invalidMoneyCannotReachLedger() {
        for(Object amount:List.of(-1,0,"1.001","NaN",130)) {
            var call=new ToolCall("refund.execute",Map.of("orderId","A100","amount",amount,"reason","test","idempotencyKey","k"),RoleCatalog.COMPENSATION);
            assertThat(actions.invoke("bad-money","s",call,true,List.of("refund.execute")).allowed()).isFalse();
        }
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isZero();
    }
    @Test void aBusinessRefusalPersistsFailureWithoutRollingBackItsAudit() throws Exception {
        var first=create("operator");
        engine.approve(first.path("taskId").asText(),true,"reviewer",first.path("revision").asLong(),"first");
        var second=create("operator");String id=second.path("taskId").asText();
        mvc.perform(post("/api/tasks/"+id+"/approve").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content(body(second,true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(engine.run(id).failureReason()).contains("累计退款");
        assertThat(new JdbcAuditLog(jdbc).byTask(id)).anyMatch(e->e.type()==io.github.tangbailin.novaorg.audit.AuditEventType.APPROVAL_GRANTED);
        assertThat(new RefundLedger(jdbc,new TransactionTemplate(manager)).count()).isEqualTo(1);
    }
}
