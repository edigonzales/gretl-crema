package ch.so.agi.gretl.runtime.runner;

/** Terminal or current state of one selected task. */
public enum TaskStatus {
    PENDING, RUNNING, SUCCEEDED, SKIPPED, FAILED, CANCELLED, NOT_RUN
}
