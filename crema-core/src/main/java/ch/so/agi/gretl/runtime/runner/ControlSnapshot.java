package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.CancelReason;
import java.util.Optional;

/** Immutable control state captured at a failure-arbitration boundary. */
public record ControlSnapshot(
        Optional<CancelReason> cancellationReason,
        boolean finishRequested,
        Optional<String> finishReason,
        boolean budgetExpired) {

    public ControlSnapshot {
        java.util.Objects.requireNonNull(cancellationReason, "cancellationReason");
        java.util.Objects.requireNonNull(finishReason, "finishReason");
    }

    public static ControlSnapshot empty() {
        return new ControlSnapshot(Optional.empty(), false, Optional.empty(), false);
    }
}
