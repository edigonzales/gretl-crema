package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.LogLevel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Internal, non-controlling lifecycle/log event. */
public record RunEvent(
        String eventName, String message, Instant timestamp, LogLevel level,
        Optional<UUID> runId, Optional<String> jobName,
        Optional<String> taskId, Optional<String> invocationId,
        Optional<String> operationId, String phase, String outcome,
        long durationMillis, Map<String, String> attributes,
        Optional<FailureInfo> failure) {

    public RunEvent {
        text(eventName, "eventName");
        message = message == null ? "" : message;
        java.util.Objects.requireNonNull(timestamp, "timestamp");
        java.util.Objects.requireNonNull(level, "level");
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(jobName, "jobName");
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(operationId, "operationId");
        java.util.Objects.requireNonNull(failure, "failure");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        attributes = Map.copyOf(attributes);
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
