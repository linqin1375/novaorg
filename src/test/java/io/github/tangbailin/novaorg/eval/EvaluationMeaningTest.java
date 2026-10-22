package io.github.tangbailin.novaorg.eval;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EvaluationMeaningTest {
    private final ObjectMapper json=new ObjectMapper();
    private EvalCase expected() throws Exception {
        return json.readValue("""
            {"id":"checks","goal":"test","expectRoles":[],"forbidRoles":[],"expectTools":["order.detail"],
             "forbidExecutedTools":[],"expectCitationDocs":["service-sla.md"],"forbidCitationDocs":[],
             "expectSuccessfulTools":["order.detail"],"requireAnswerCitation":true}
            """,EvalCase.class);
    }
    private RunTrace trace(String status,boolean executed,boolean cited) throws Exception {
        return json.readValue("""
            {"status":"%s","roles":[],"toolsCalled":["order.detail"],"toolsExecuted":%s,
             "toolsDenied":[],"citationDocs":["service-sla.md"],"permissionViolation":false,
             "modelToolsRequested":["order.detail"],"modelToolsExecuted":%s,"answerCitationDocs":%s,
             "invalidCitationReferences":0,"refundCount":0,"usageEstimated":true,"steps":[],"failureReason":"budget"}
            """.formatted(status,executed?"[\"order.detail\"]":"[]",executed?"[\"order.detail\"]":"[]",
                    cited?"[\"service-sla.md\"]":"[]"),RunTrace.class);
    }
    @Test void requestedButUnsuccessfulToolDoesNotSatisfyExecutionRequirement() throws Exception {
        var result=new Grader().grade(expected(),trace("DONE",false,true));
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(s->s.contains("没有成功执行"));
    }
    @Test void retrievedDocumentIsNotAnAnswerCitation() throws Exception {
        var result=new Grader().grade(expected(),trace("DONE",true,false));
        assertThat(result.citationHit()).isTrue();
        assertThat(result.answerCitationHit()).isFalse();
        assertThat(result.passed()).isFalse();
    }
    @Test void failedRunCannotPassByMatchingRoutingToolsAndDocuments() throws Exception {
        var result=new Grader().grade(expected(),trace("FAILED",true,true));
        assertThat(result.passed()).isFalse();assertThat(result.completed()).isFalse();
    }
}
