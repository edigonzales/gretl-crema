package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.Diagnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable terminal result of one sequential job run. */
public record JobOutcome(
        UUID runId, UUID definitionId, String jobName,
        RunStatus status, int exitCode, List<String> requestedTargets,
        List<TaskOutcome> tasks, List<Diagnostic> diagnostics,
        Optional<FailureInfo> primaryFailure, List<FailureInfo> secondaryFailures,
        Instant startedAt, Instant finishedAt, long durationMillis,
        boolean finishRequested, Optional<String> finishReason,
        boolean workRetained, Optional<ch.so.agi.gretl.runtime.api.CancelReason> cancelReason) {

    public JobOutcome {
        java.util.Objects.requireNonNull(cancelReason, "cancelReason");
        if ((status == RunStatus.CANCELLED) != cancelReason.isPresent()) {
            throw new IllegalArgumentException("cancelReason required exactly for CANCELLED");
        }

        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(definitionId, "definitionId");
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        java.util.Objects.requireNonNull(status, "status");
        if (exitCode != status.exitCode()) {
            throw new IllegalArgumentException("exitCode does not match status");
        }
        requestedTargets = List.copyOf(requestedTargets);
        tasks = List.copyOf(tasks);
        diagnostics = List.copyOf(diagnostics);
        java.util.Objects.requireNonNull(primaryFailure, "primaryFailure");
        secondaryFailures = List.copyOf(secondaryFailures);
        java.util.Objects.requireNonNull(startedAt, "startedAt");
        java.util.Objects.requireNonNull(finishedAt, "finishedAt");
        java.util.Objects.requireNonNull(finishReason, "finishReason");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (status == RunStatus.FAILED && primaryFailure.isEmpty()) {
            throw new IllegalArgumentException("FAILED run requires a primary failure");
        }
        if (status != RunStatus.FAILED && primaryFailure.isPresent()) {
            throw new IllegalArgumentException("only FAILED run may carry a primary failure");
        }
    }

    public Optional<TaskOutcome> task(String taskId) {
        return tasks.stream().filter(task -> task.taskId().equals(taskId)).findFirst();
    }

    public List<InvocationOutcome> invocations() {
        return tasks.stream().flatMap(task -> task.invocations().stream()).toList();
    }
}
