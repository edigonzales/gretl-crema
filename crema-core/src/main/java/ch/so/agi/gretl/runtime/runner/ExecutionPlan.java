package ch.so.agi.gretl.runtime.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable, deterministic execution plan for one job definition. */
public record ExecutionPlan(
        UUID definitionId, List<String> requestedTargets,
        List<TaskDefinition<?>> selectedTasks,
        Map<String, Set<String>> dataPredecessors,
        List<PlanEdge> edges) {

    public ExecutionPlan {
        java.util.Objects.requireNonNull(definitionId, "definitionId");
        requestedTargets = List.copyOf(requestedTargets);
        selectedTasks = List.copyOf(selectedTasks);
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        dataPredecessors.forEach((key, value) ->
                copied.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(value))));
        dataPredecessors = Collections.unmodifiableMap(copied);
        edges = List.copyOf(edges);
    }

    public Set<String> selectedIds() {
        return selectedTasks.stream().map(TaskDefinition::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean contains(String taskId) {
        return selectedTasks.stream().anyMatch(task -> task.id().equals(taskId));
    }

    public Set<String> dataPredecessors(String taskId) {
        return dataPredecessors.getOrDefault(taskId, Set.of());
    }
}
