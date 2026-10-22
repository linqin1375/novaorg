package io.github.tangbailin.novaorg.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory audit trail.
 *
 * <p>Deliberately append-only and queryable: a compliance officer must be able to answer
 * "who did what, under which authority, and was it approved" without reading application logs.
 */
public final class InMemoryAuditLog implements AuditLog {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> all() {
        List<AuditEvent> copy = new ArrayList<>(events);
        return Collections.unmodifiableList(copy);
    }

    @Override
    public List<AuditEvent> byTask(String taskId) {
        return events.stream().filter(e -> taskId.equals(e.taskId())).collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> byRole(String roleId) {
        return events.stream().filter(e -> roleId.equals(e.roleId())).collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> byType(AuditEventType type) {
        return events.stream().filter(e -> e.type() == type).collect(Collectors.toList());
    }

    @Override
    public void clear() {
        events.clear();
    }
}
