package ch.so.agi.gretl.runtime.api;

/** Unchecked exception raised when a controlled cancellation is detected. */
public final class TaskCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final CancelReason reason;

    public TaskCancelledException(CancelReason reason) {
        super("Execution cancelled: " + java.util.Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public CancelReason reason() {
        return reason;
    }
}
