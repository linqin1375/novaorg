package io.github.tangbailin.novaorg.persistence;

import io.github.tangbailin.novaorg.runtime.TaskRun;
import java.util.*;
import java.util.function.Function;

/** Unit tests and isolated offline evaluations only. */
public final class MemoryTaskStore implements TaskStore {
    private final Map<String,TaskRun> runs = new LinkedHashMap<>();
    public synchronized void create(TaskRun run) { runs.putIfAbsent(run.taskId(),run); }
    public synchronized TaskRun get(String id) { return runs.get(id); }
    public synchronized List<TaskRun> all() { return List.copyOf(runs.values()); }
    public synchronized <T> T update(String id, Function<TaskRun,T> mutation) {
        TaskRun run = runs.get(id);
        if (run == null) throw new IllegalArgumentException("unknown task: " + id);
        return mutation.apply(run);
    }
}
