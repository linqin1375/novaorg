package io.github.tangbailin.novaorg.persistence;

import io.github.tangbailin.novaorg.audit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

public final class JdbcAuditLog implements AuditLog {
    private final JdbcTemplate jdbc;
    public JdbcAuditLog(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void record(AuditEvent event) { jdbc.update("INSERT INTO audit_events(event_id,task_id,payload) VALUES (?,?,?)",UUID.randomUUID().toString(),event.taskId(),JsonCodec.write(event)); }
    public List<AuditEvent> all() { return jdbc.query("SELECT payload FROM audit_events",(rs,n)->JsonCodec.read(rs.getString(1),AuditEvent.class)); }
    public List<AuditEvent> byTask(String id) { return jdbc.query("SELECT payload FROM audit_events WHERE task_id=?",(rs,n)->JsonCodec.read(rs.getString(1),AuditEvent.class),id); }
    public List<AuditEvent> byRole(String id) { return all().stream().filter(e->Objects.equals(id,e.roleId())).toList(); }
    public List<AuditEvent> byType(AuditEventType type) { return all().stream().filter(e->e.type()==type).toList(); }
    public void clear() { throw new UnsupportedOperationException("Persistent audit logs cannot be cleared through the application"); }
}
