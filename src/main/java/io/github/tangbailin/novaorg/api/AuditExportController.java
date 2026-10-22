package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.audit.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit export.
 *
 * <p>An audit trail that can only be read on screen is not an audit trail: compliance teams need
 * to hand it to someone else (an assessor, a regulator, an incident review). Export is therefore a
 * first-class endpoint, and it is itself an audited action - see {@code AUDIT_EXPORTED} below.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditExportController {

    private final TaskRunService service;

    public AuditExportController(TaskRunService service) {
        this.service = service;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String requestedBy) {

        List<AuditEvent> events = service.auditEvents(taskId, roleId, type);
        service.recordAuditExport(TaskAccess.user(), taskId, roleId, type, format, events.size());

        String body;
        MediaType mediaType;
        String extension;
        switch (format.toLowerCase()) {
            case "json" -> {
                body = toJson(events);
                mediaType = MediaType.APPLICATION_JSON;
                extension = "json";
            }
            case "md", "markdown" -> {
                body = toMarkdown(events, taskId, roleId, type);
                mediaType = MediaType.TEXT_PLAIN;
                extension = "md";
            }
            default -> {
                body = toCsv(events);
                mediaType = new MediaType("text", "csv", StandardCharsets.UTF_8);
                extension = "csv";
            }
        }

        String fileName = "novaorg-audit-" + (taskId == null ? "all" : taskId) + "." + extension;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private String toCsv(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        // BOM so Excel on Windows opens UTF-8 Chinese correctly instead of showing mojibake
        sb.append('\uFEFF');
        sb.append("at,traceId,taskId,stepId,roleId,type,summary,attributes\n");
        for (AuditEvent e : events) {
            sb.append(csv(e.at().toString())).append(',')
                    .append(csv(e.traceId())).append(',')
                    .append(csv(e.taskId())).append(',')
                    .append(csv(e.stepId())).append(',')
                    .append(csv(e.roleId())).append(',')
                    .append(csv(e.type().name())).append(',')
                    .append(csv(e.summary())).append(',')
                    .append(csv(flatten(e.attributes())))
                    .append('\n');
        }
        return sb.toString();
    }

    private String toJson(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            sb.append("  {")
                    .append("\"at\":\"").append(escape(e.at().toString())).append("\",")
                    .append("\"traceId\":\"").append(escape(e.traceId())).append("\",")
                    .append("\"taskId\":\"").append(escape(e.taskId())).append("\",")
                    .append("\"stepId\":\"").append(escape(e.stepId())).append("\",")
                    .append("\"roleId\":\"").append(escape(e.roleId())).append("\",")
                    .append("\"type\":\"").append(e.type().name()).append("\",")
                    .append("\"summary\":\"").append(escape(e.summary())).append("\",")
                    .append("\"attributes\":{");
            int j = 0;
            for (Map.Entry<String, Object> attr : e.attributes().entrySet()) {
                if (j++ > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(attr.getKey())).append("\":\"")
                        .append(escape(String.valueOf(attr.getValue()))).append('"');
            }
            sb.append("}}");
            sb.append(i == events.size() - 1 ? "\n" : ",\n");
        }
        return sb.append("]").toString();
    }

    private String toMarkdown(List<AuditEvent> events, String taskId, String roleId, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 审计日志导出\n\n");
        sb.append("- 任务过滤：").append(taskId == null ? "全部" : taskId).append('\n');
        sb.append("- 角色过滤：").append(roleId == null ? "全部" : roleId).append('\n');
        sb.append("- 事件类型过滤：").append(type == null ? "全部" : type).append('\n');
        sb.append("- 记录数：").append(events.size()).append("\n\n");
        sb.append("| 时间 | traceId | 角色 | 步骤 | 事件 | 说明 |\n|---|---|---|---|---|---|\n");
        for (AuditEvent e : events) {
            sb.append("| ").append(e.at()).append(" | ").append(e.traceId())
                    .append(" | ").append(e.roleId()).append(" | ").append(e.stepId())
                    .append(" | ").append(e.type().name())
                    .append(" | ").append(e.summary().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private static String flatten(Map<String, Object> attributes) {
        StringBuilder sb = new StringBuilder();
        attributes.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? '"' + escaped + '"' : escaped;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
