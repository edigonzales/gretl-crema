package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskRef;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Frozen task definition, including resolved links. */
public record TaskDefinition<R>(
        TaskRef<R> ref, int registrationIndex, String description,
        List<TaskRef<?>> dependsOn, List<TaskRef<?>> after,
        TaskCondition condition, Duration timeout, TaskBody<R> body,
        OperationType<?, R> operation, SourceLocation source) {

    public TaskDefinition {
        Objects.requireNonNull(ref, "ref");
        if (registrationIndex < 0) {
            throw new IllegalArgumentException("registrationIndex must not be negative");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        dependsOn = List.copyOf(dependsOn);
        after = List.copyOf(after);
        Objects.requireNonNull(body, "body");
        source = source == null ? SourceLocation.unknown() : source;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public String id() {
        return ref.id();
    }

    public Class<R> resultType() {
        return ref.resultType();
    }
}
