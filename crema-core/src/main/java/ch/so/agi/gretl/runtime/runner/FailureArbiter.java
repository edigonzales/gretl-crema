package ch.so.agi.gretl.runtime.runner;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic task failure precedence. It analyzes explicit work/control
 * values, never log severity, clocks or business codes.
 */
public final class FailureArbiter {

    public TaskCompletion resolve(
            WorkCompletion work, ControlSnapshot control, List<Throwable> closeErrors) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(control, "control");
        List<Throwable> failures = closeErrors == null ? List.of() : closeErrors;
        List<FailureInfo> closeFailures = failures.stream().map(FailureInfo::from).toList();

        if (work instanceof WorkCompletion.Failure failure) {
            for (Throwable closeError : failures) {
                if (closeError != failure.error()) {
                    failure.error().addSuppressed(closeError);
                }
            }
            return TaskCompletion.failed(FailureInfo.from(failure.error()), closeFailures);
        }

        if (work instanceof WorkCompletion.Cancelled cancelled) {
            java.util.ArrayList<FailureInfo> secondary = new java.util.ArrayList<>();
            if (cancelled.secondary() != null) {
                secondary.add(FailureInfo.from(cancelled.secondary()));
            }
            failures.stream().filter(error -> !(error instanceof
                    ch.so.agi.gretl.runtime.api.TaskCancelledException)
                            || error.getCause() != null || error.getSuppressed().length > 0)
                    .map(FailureInfo::from).forEach(secondary::add);
            return TaskCompletion.cancelled(control.cancellationReason().orElseThrow(), secondary);
        }

        // Release errors are in controlled observation order. An earlier real
        // close error must not be hidden by a later timeout.
        if (!failures.isEmpty() && failures.getFirst() instanceof
                ch.so.agi.gretl.runtime.api.TaskCancelledException) {
            return resolve(new WorkCompletion.Cancelled(), control, failures);
        }

        if (work instanceof WorkCompletion.Skipped skipped) {
            if (!failures.isEmpty()) {
                return failureFromCloseErrors(failures);
            }
            return TaskCompletion.skipped(skipped.reason());
        }

        if (work instanceof WorkCompletion.Finished finished) {
            if (!failures.isEmpty()) {
                return failureFromCloseErrors(failures);
            }
            if (control.cancellationReason().isPresent()) {
                return TaskCompletion.cancelled(control.cancellationReason().orElseThrow(), closeFailures);
            }
            return TaskCompletion.skipped(finished.reason());
        }

        WorkCompletion.Value value = (WorkCompletion.Value) work;
        if (control.finishRequested()) {
            if (!failures.isEmpty()) {
                return failureFromCloseErrors(failures);
            }
            return TaskCompletion.skipped(
                    control.finishReason().orElse("JOB_FINISHED"));
        }
        if (!failures.isEmpty()) {
            return failureFromCloseErrors(failures);
        }
        if (control.cancellationReason().isPresent()) {
            return TaskCompletion.cancelled(control.cancellationReason().orElseThrow(), List.of());
        }
        return TaskCompletion.succeeded(value.value());
    }

    private static TaskCompletion failureFromCloseErrors(List<Throwable> closeErrors) {
        Throwable primary = closeErrors.get(0);
        for (int index = 1; index < closeErrors.size(); index++) {
            if (closeErrors.get(index) != primary) {
                primary.addSuppressed(closeErrors.get(index));
            }
        }
        List<FailureInfo> secondary = closeErrors.subList(1, closeErrors.size()).stream()
                .map(FailureInfo::from).toList();
        return TaskCompletion.failed(FailureInfo.from(primary), secondary);
    }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    /** Explicit result of the executable work, independent of close failures. */
    public sealed interface WorkCompletion {
        record Value(Object value) implements WorkCompletion {
        }

        record Failure(Throwable error) implements WorkCompletion {
            public Failure {
                Objects.requireNonNull(error, "error");
            }
        }

        record Finished(String reason) implements WorkCompletion {
            public Finished {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason must not be blank");
                }
            }
        }

        record Skipped(String reason) implements WorkCompletion {
            public Skipped {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason must not be blank");
                }
            }
        }

        record Cancelled(Throwable secondary) implements WorkCompletion {
            public Cancelled() {
                this(null);
            }
        }
    }
}
