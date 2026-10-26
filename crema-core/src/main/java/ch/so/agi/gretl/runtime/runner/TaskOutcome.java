package ch.so.agi.gretl.runtime.runner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Immutable terminal snapshot of one selected task. */
public record TaskOutcome(
        String taskId, TaskStatus status, String reason, ResultSlot slot,
        Optional<Instant> startedAt, Optional<Instant> finishedAt,
        long durationMillis, List<InvocationOutcome> invocations,
        Optional<FailureInfo> failure, List<FailureInfo> secondaryFailures,
        Optional<ch.so.agi.gretl.runtime.api.CancelReason> cancelReason, String failurePhase) {

    public TaskOutcome {
        java.util.Objects.requireNonNull(cancelReason, "cancelReason");
        if ((status == TaskStatus.CANCELLED) != cancelReason.isPresent()) {
            throw new IllegalArgumentException("cancelReason required exactly for CANCELLED");
        }

        text(taskId, "taskId");
        java.util.Objects.requireNonNull(status, "status");
        if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
            throw new IllegalArgumentException("TaskOutcome requires a terminal status");
        }
        java.util.Objects.requireNonNull(slot, "slot");
        java.util.Objects.requireNonNull(startedAt, "startedAt");
        java.util.Objects.requireNonNull(finishedAt, "finishedAt");
        java.util.Objects.requireNonNull(failure, "failure");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (status == TaskStatus.NOT_RUN) {
            if (startedAt.isPresent() || finishedAt.isPresent() || durationMillis != 0) {
                throw new IllegalArgumentException("NOT_RUN cannot have task times");
            }
            if (slot != ResultSlot.NoValue.INSTANCE || failure.isPresent()) {
                throw new IllegalArgumentException("NOT_RUN cannot have a value or failure");
            }
        } else if (startedAt.isEmpty() || finishedAt.isEmpty()) {
            throw new IllegalArgumentException("executed task requires start/end times");
        }
        if (status == TaskStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("FAILED task requires a failure");
        }
        if ((status == TaskStatus.SKIPPED || status == TaskStatus.CANCELLED)
                && slot != ResultSlot.NoValue.INSTANCE) {
            throw new IllegalArgumentException(status + " cannot carry a value");
        }
        invocations = List.copyOf(invocations);
        secondaryFailures = List.copyOf(secondaryFailures);
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
