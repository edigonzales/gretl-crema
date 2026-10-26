package ch.so.agi.gretl.runtime.runner;

import java.util.List;

/** Immutable terminal completion proposed for one task. */
public record TaskCompletion(
        TaskStatus status, ResultSlot slot, String reason,
        FailureInfo primaryFailure, List<FailureInfo> secondaryFailures,
        java.util.Optional<ch.so.agi.gretl.runtime.api.CancelReason> cancelReason, String failurePhase) {

    public TaskCompletion(TaskStatus status, ResultSlot slot, String reason,
                          FailureInfo primaryFailure, List<FailureInfo> secondaryFailures) {
        this(status, slot, reason, primaryFailure, secondaryFailures, java.util.Optional.empty(), null);
    }

    public TaskCompletion {
        java.util.Objects.requireNonNull(cancelReason, "cancelReason");
        if ((status == TaskStatus.CANCELLED) != cancelReason.isPresent()) {
            throw new IllegalArgumentException("cancelReason required exactly for CANCELLED");
        }

        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(slot, "slot");
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank when present");
        }
        if (status == TaskStatus.FAILED && primaryFailure == null) {
            throw new IllegalArgumentException("FAILED requires a primary failure");
        }
        if (status != TaskStatus.FAILED && primaryFailure != null) {
            throw new IllegalArgumentException("only FAILED may carry a primary failure");
        }
        if ((status == TaskStatus.SKIPPED || status == TaskStatus.NOT_RUN)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(status + " requires a reason");
        }
        if ((status == TaskStatus.SKIPPED || status == TaskStatus.CANCELLED
                || status == TaskStatus.NOT_RUN) && slot != ResultSlot.NoValue.INSTANCE) {
            throw new IllegalArgumentException(status + " cannot carry a value");
        }
        secondaryFailures = List.copyOf(secondaryFailures);
    }

    public static TaskCompletion succeeded(Object value) {
        return new TaskCompletion(TaskStatus.SUCCEEDED,
                value == null ? ResultSlot.NoValue.INSTANCE : new ResultSlot.Value(value),
                null, null, List.of());
    }

    public static TaskCompletion succeeded(Object value, List<FailureInfo> secondary) {
        return new TaskCompletion(TaskStatus.SUCCEEDED,
                value == null ? ResultSlot.NoValue.INSTANCE : new ResultSlot.Value(value),
                null, null, secondary);
    }

    public static TaskCompletion failed(Throwable primary) {
        return failed(primary, List.of());
    }

    public static TaskCompletion failed(Throwable primary, List<FailureInfo> secondary) {
        return new TaskCompletion(TaskStatus.FAILED, ResultSlot.NoValue.INSTANCE,
                null, FailureInfo.from(primary), secondary);
    }

    public static TaskCompletion failed(FailureInfo primary, List<FailureInfo> secondary) {
        return new TaskCompletion(TaskStatus.FAILED, ResultSlot.NoValue.INSTANCE,
                null, primary, secondary);
    }

    public static TaskCompletion skipped(String reason) {
        return new TaskCompletion(TaskStatus.SKIPPED, ResultSlot.NoValue.INSTANCE,
                reason, null, List.of());
    }

    public static TaskCompletion cancelled(ch.so.agi.gretl.runtime.api.CancelReason reason, List<FailureInfo> secondary) {
        return new TaskCompletion(TaskStatus.CANCELLED, ResultSlot.NoValue.INSTANCE,
                null, null, secondary, java.util.Optional.of(reason), null);
    }

    public static TaskCompletion notRun(String reason) {
        return new TaskCompletion(TaskStatus.NOT_RUN, ResultSlot.NoValue.INSTANCE,
                reason, null, List.of());
    }
}
