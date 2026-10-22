package io.github.tangbailin.novaorg.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRunService service;

    public TaskController(TaskRunService service) {
        this.service = service;
    }

    @PostMapping
    public Dtos.TaskView create(@RequestBody Dtos.CreateTaskRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank() || request.goal().length()>4000) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        return service.submitTask(request.goal(), TaskAccess.user(), "gate");
    }

    @GetMapping
    public List<Dtos.TaskView> list() {
        return service.listTasks();
    }

    @PostMapping("/{taskId}/clarify")
    public Dtos.TaskView clarify(@PathVariable String taskId,@RequestBody Dtos.ClarifyRequest request) {
        return service.clarify(taskId,request.answer(),request.revision());
    }

    @GetMapping("/{taskId}")
    public Dtos.TaskView get(@PathVariable String taskId) {
        return service.getTask(taskId);
    }

    @GetMapping("/{taskId}/audit")
    public List<Dtos.AuditEventView> audit(@PathVariable String taskId) {
        return service.audit(taskId, null, null);
    }

    @PostMapping("/{taskId}/approve")
    public Dtos.TaskView approve(@PathVariable String taskId,
                                 @RequestBody Dtos.ApprovalRequest request) {
        return service.approve(taskId, request.granted(), TaskAccess.user(), request.reason(), request.revision());
    }

    @PostMapping("/{taskId}/resume")
    public Dtos.TaskView resume(@PathVariable String taskId) { return service.resume(taskId); }

    @GetMapping("/audit")
    public List<Dtos.AuditEventView> auditQuery(@RequestParam(required = false) String taskId,
                                                @RequestParam(required = false) String roleId,
                                                @RequestParam(required = false) String type) {
        return service.audit(taskId, roleId, type);
    }

    @ExceptionHandler(TaskRunService.TaskNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(TaskRunService.TaskNotFoundException e) {
        return new ErrorResponse("TASK_NOT_FOUND", e.getMessage());
    }

    /**
     * 409 rather than 400: the request was well-formed, it conflicts with a governance rule
     * (four-eyes) - the client cannot fix it by editing the payload, it has to use another person.
     */
    @ExceptionHandler(TaskRunService.SeparationOfDutiesException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse separationOfDuties(TaskRunService.SeparationOfDutiesException e) {
        return new ErrorResponse("SEPARATION_OF_DUTIES", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(IllegalArgumentException e) {
        return new ErrorResponse("BAD_REQUEST", e.getMessage());
    }

    public record ErrorResponse(String code, String message) {
    }
}
