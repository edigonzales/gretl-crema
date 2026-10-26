package ch.so.agi.gretl.runtime.runner;

/** Mode of parameter resolution; only RUN requires all required values. */
public enum ParameterMode {
    RUN(true), CHECK(false), PLAN(false);

    private final boolean complete;

    ParameterMode(boolean complete) {
        this.complete = complete;
    }

    public boolean requiresCompleteSet() {
        return complete;
    }
}
