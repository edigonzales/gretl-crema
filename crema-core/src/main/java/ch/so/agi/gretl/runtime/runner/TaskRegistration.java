package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Declarative task registration before a JobDefinition is frozen. */
public record TaskRegistration<R>(
        String id, Class<R> resultType, String description,
        List<TaskLink> dependsOn, List<TaskLink> after,
        TaskCondition condition, Duration timeout, TaskBody<R> body,
        OperationType<?, R> operation, SourceLocation source) {

    public TaskRegistration {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(resultType, "resultType");
        description = description == null || description.isBlank() ? id : description;
        dependsOn = List.copyOf(dependsOn);
        after = List.copyOf(after);
        Objects.requireNonNull(body, "body");
        source = source == null ? SourceLocation.unknown() : source;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static <R> Builder<R> builder(String id, Class<R> resultType) {
        return new Builder<>(id, resultType);
    }

    /** Small builder for tests and Java task libraries. */
    public static final class Builder<R> {
        private final String id;
        private final Class<R> resultType;
        private String description;
        private final List<TaskLink> dependsOn = new ArrayList<>();
        private final List<TaskLink> after = new ArrayList<>();
        private TaskCondition condition;
        private Duration timeout;
        private TaskBody<R> body;
        private OperationType<?, R> operation;
        private SourceLocation source = SourceLocation.unknown();

        public Builder(String id, Class<R> resultType) {
            this.id = id;
            this.resultType = resultType;
        }

        public Builder<R> description(String value) {
            this.description = value;
            return this;
        }

        public Builder<R> dependsOn(String... taskIds) {
            for (String taskId : taskIds) {
                dependsOn.add(TaskLink.named(taskId));
            }
            return this;
        }

        public Builder<R> dependsOn(TaskRef<?>... refs) {
            for (TaskRef<?> ref : refs) {
                dependsOn.add(TaskLink.of(ref));
            }
            return this;
        }

        public Builder<R> dependsOn(TaskLink... links) {
            dependsOn.addAll(java.util.List.of(links));
            return this;
        }

        public Builder<R> dependsOn(java.util.List<TaskLink> links) {
            dependsOn.addAll(links);
            return this;
        }

        public Builder<R> after(String... taskIds) {
            for (String taskId : taskIds) {
                after.add(TaskLink.named(taskId));
            }
            return this;
        }

        public Builder<R> after(TaskRef<?>... refs) {
            for (TaskRef<?> ref : refs) {
                after.add(TaskLink.of(ref));
            }
            return this;
        }

        public Builder<R> after(TaskLink... links) {
            after.addAll(java.util.List.of(links));
            return this;
        }

        public Builder<R> after(java.util.List<TaskLink> links) {
            after.addAll(links);
            return this;
        }

        public Builder<R> when(TaskCondition condition) {
            this.condition = condition;
            return this;
        }

        public Builder<R> timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder<R> body(TaskBody<R> value) {
            this.body = value;
            return this;
        }

        public Builder<R> operation(OperationType<?, R> value) {
            this.operation = value;
            return this;
        }

        public Builder<R> source(SourceLocation value) {
            this.source = value;
            return this;
        }

        public TaskRegistration<R> build() {
            return new TaskRegistration<>(id, resultType, description,
                    dependsOn, after, condition, timeout, body, operation, source);
        }
    }
}
