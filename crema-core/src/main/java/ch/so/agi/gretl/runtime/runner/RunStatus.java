package ch.so.agi.gretl.runtime.runner;

/** Terminal state of one job run. */
public enum RunStatus {
    SUCCEEDED, NO_DATA, FAILED, CANCELLED;

    public int exitCode() {
        return switch (this) {
            case SUCCEEDED, NO_DATA -> 0;
            case FAILED -> 10;
            case CANCELLED -> 20;
        };
    }
}
