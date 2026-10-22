package io.github.tangbailin.novaorg.persistence;

import io.github.tangbailin.novaorg.runtime.TaskRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.function.Function;

public final class JdbcTaskStore implements TaskStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcTaskStore(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public void create(TaskRun run) {
        if (get(run.taskId()) == null) jdbc.update("INSERT INTO task_runs(task_id,payload) VALUES (?,?)",run.taskId(),JsonCodec.write(run));
    }
    public TaskRun get(String id) {
        return jdbc.query("SELECT payload FROM task_runs WHERE task_id=?",(rs,n)->JsonCodec.read(rs.getString(1),TaskRun.class),id).stream().findFirst().orElse(null);
    }
    public List<TaskRun> all() {
        return jdbc.query("SELECT payload FROM task_runs",(rs,n)->JsonCodec.read(rs.getString(1),TaskRun.class));
    }
    public <T> T update(String id, Function<TaskRun,T> mutation) {
        return tx.execute(status -> {
            var rows=jdbc.query("SELECT payload FROM task_runs WHERE task_id=? FOR UPDATE",(rs,n)->JsonCodec.read(rs.getString(1),TaskRun.class),id);
            if(rows.isEmpty()) throw new IllegalArgumentException("unknown task: "+id);
            TaskRun run=rows.get(0);
            T result=mutation.apply(run);
            jdbc.update("UPDATE task_runs SET payload=? WHERE task_id=?",JsonCodec.write(run),id);
            return result;
        });
    }
}
