package ch.so.agi.gretl.runtime.runner;

/** Workspace retention mode for a run (spec 06/OPS-040). */
public enum KeepWork {
    NEVER, ON_FAILURE, ALWAYS;

    public boolean retain(RunStatus status) {
        return switch (this) {
            case NEVER -> false;
            case ALWAYS -> true;
            case ON_FAILURE -> status == RunStatus.FAILED || status == RunStatus.CANCELLED;
        };
    }
}
