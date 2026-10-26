package ch.so.agi.gretl.runtime.runner;

/**
 * Internal control signal for a requested successful job finish. It is not a
 * real business failure and must not be exposed as an ordinary task error.
 */
public final class FinishSignal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    public FinishSignal(String reason) {
        super("job finished: " + reason, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
