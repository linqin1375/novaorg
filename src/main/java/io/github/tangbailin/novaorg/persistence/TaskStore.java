package io.github.tangbailin.novaorg.persistence;

import io.github.tangbailin.novaorg.runtime.TaskRun;
import java.util.List;
import java.util.function.Function;

public interface TaskStore {
    void create(TaskRun run);
    TaskRun get(String id);
    List<TaskRun> all();
    <T> T update(String id, Function<TaskRun,T> mutation);
}
