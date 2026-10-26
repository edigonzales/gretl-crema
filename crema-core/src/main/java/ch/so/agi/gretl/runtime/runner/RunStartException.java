package ch.so.agi.gretl.runtime.runner;

/** Failed preparation before exclusive workspace ownership and run.started. */
public final class RunStartException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RunStartException(Throwable cause) {
        super("RUN_WORKSPACE_PREPARATION_FAILED: " + cause.getMessage(), cause);
    }
}
