package io.github.tangbailin.novaorg.audit;

import java.util.List;

public interface AuditLog {

    void record(AuditEvent event);

    List<AuditEvent> all();

    List<AuditEvent> byTask(String taskId);

    List<AuditEvent> byRole(String roleId);

    List<AuditEvent> byType(AuditEventType type);

    void clear();
}
