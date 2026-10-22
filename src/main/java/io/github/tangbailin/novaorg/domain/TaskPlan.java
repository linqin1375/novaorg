package io.github.tangbailin.novaorg.domain;

import java.util.List;
import java.util.Optional;

/** The output of the planner: a goal decomposed into staffed work orders. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class TaskPlan {

    private final String taskId;
    private final String goal;
    private final Complexity complexity;
    private final List<TaskStep> steps;
    private final String rationale;
    private final String requestIntent;
    public String requestIntent() { return requestIntent; }

    public TaskPlan(String id,String goal,Complexity complexity,List<TaskStep> steps,String rationale) {
        this(id,goal,complexity,steps,rationale,"LEGACY");
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public TaskPlan(@com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId,
                    @com.fasterxml.jackson.annotation.JsonProperty("goal") String goal,
                    @com.fasterxml.jackson.annotation.JsonProperty("complexity") Complexity complexity,
                    @com.fasterxml.jackson.annotation.JsonProperty("steps") List<TaskStep> steps,
                    @com.fasterxml.jackson.annotation.JsonProperty("rationale") String rationale,
                    @com.fasterxml.jackson.annotation.JsonProperty("requestIntent") String requestIntent) {
        this.taskId = taskId;
        this.goal = goal;
        this.complexity = complexity;
        this.steps = List.copyOf(steps);
        this.rationale = rationale;
        this.requestIntent = requestIntent==null?"LEGACY":requestIntent;
    }

    public String taskId() {
        return taskId;
    }

    public String goal() {
        return goal;
    }

    public Complexity complexity() {
        return complexity;
    }

    public List<TaskStep> steps() {
        return steps;
    }

    public String rationale() {
        return rationale;
    }

    public Optional<TaskStep> step(String stepId) {
        return steps.stream().filter(s -> s.stepId().equals(stepId)).findFirst();
    }

    public boolean requiresApprovalGate() {
        return complexity == Complexity.COMPLEX;
    }
}
