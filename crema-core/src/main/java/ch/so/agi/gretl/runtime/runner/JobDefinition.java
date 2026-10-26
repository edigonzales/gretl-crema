package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Frozen, immutable registration of one job definition. */
public record JobDefinition(
        UUID definitionId, List<TaskDefinition<?>> tasks,
        List<ParameterDefinition> parameters, List<String> defaultTargets,
        List<CleanupDefinition> cleanups, Path jobRoot) {

    public JobDefinition {
        Objects.requireNonNull(definitionId, "definitionId");
        tasks = List.copyOf(tasks);
        parameters = List.copyOf(parameters);
        defaultTargets = List.copyOf(defaultTargets);
        cleanups = List.copyOf(cleanups);
        Objects.requireNonNull(jobRoot, "jobRoot");
        jobRoot = jobRoot.toAbsolutePath().normalize();

        Set<String> taskIds = new HashSet<>();
        Set<Integer> indexes = new HashSet<>();
        for (TaskDefinition<?> task : tasks) {
            if (!(task.ref() instanceof JobTaskRef<?> ref)
                    || !definitionId.equals(ref.definitionId())) {
                throw new IllegalArgumentException("task reference belongs to another definition");
            }
            if (!taskIds.add(task.id())) {
                throw new IllegalArgumentException("duplicate task id: " + task.id());
            }
            if (!indexes.add(task.registrationIndex())) {
                throw new IllegalArgumentException("duplicate registration index");
            }
        }
        Set<String> targets = new HashSet<>();
        for (String target : defaultTargets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("default target must not be blank");
            }
            if (!targets.add(target)) {
                throw new IllegalArgumentException("duplicate default target: " + target);
            }
        }
        Set<String> cleanupNames = new HashSet<>();
        for (CleanupDefinition cleanup : cleanups) {
            if (!cleanupNames.add(cleanup.name())) {
                throw new IllegalArgumentException("duplicate cleanup name: " + cleanup.name());
            }
        }
    }

    public Optional<TaskDefinition<?>> task(String id) {
        return tasks.stream().filter(task -> task.id().equals(id)).findFirst();
    }

    public Optional<TaskDefinition<?>> task(TaskRef<?> ref) {
        if (!(ref instanceof JobTaskRef<?> jobRef)
                || !definitionId.equals(jobRef.definitionId())) {
            return Optional.empty();
        }
        return task(jobRef.id());
    }

    public TaskDefinition<?> requireTask(TaskRef<?> ref) {
        return task(ref).orElseThrow(() -> new ResultAccessException(
                "FOREIGN_OR_UNKNOWN_TASK_REF",
                "TaskRef does not belong to this JobDefinition or is unknown: " + ref));
    }

    public boolean containsRef(TaskRef<?> ref) {
        return ref instanceof JobTaskRef<?> jobRef
                && definitionId.equals(jobRef.definitionId())
                && task(jobRef.id()).isPresent();
    }

    public Set<String> taskIds() {
        return tasks.stream().map(TaskDefinition::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
