package io.github.tangbailin.novaorg.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable audit record. Shaped like a span so it can be exported to
 * OpenTelemetry / ELK later without changing call sites.
 *
 * <p>{@code traceId} correlates every event produced by the same business request, which is what
 * makes the log usable during an incident: "show me everything that happened while this task ran",
 * including the events emitted by the permission gateway deep inside a step.
 */
public record AuditEvent(
        Instant at,
        String traceId,
        String taskId,
        String stepId,
        String roleId,
        AuditEventType type,
        String summary,
        Map<String, Object> attributes
) {
    public static AuditEvent of(String taskId, String stepId, String roleId,
                               AuditEventType type, String summary) {
        return of(taskId, stepId, roleId, type, summary, Map.of());
    }

    public static AuditEvent of(String taskId, String stepId, String roleId,
                               AuditEventType type, String summary, Map<String, Object> attributes) {
        Map<String, Object> attrs = new LinkedHashMap<>(attributes);
        return new AuditEvent(Instant.now(), TraceContext.currentTraceId(), taskId, stepId,
                roleId, type, summary, Map.copyOf(attrs));
    }

    /** Attribute lookup that tolerates absent keys, used by the API and the console. */
    public Object attribute(String key) {
        return attributes.get(key);
    }
}
