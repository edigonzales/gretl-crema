package ch.so.agi.gretl.runtime.api;

/**
 * Monotonic remaining time budget. An empty {@link #remaining()} means no limit;
 * an expired budget reports {@link java.time.Duration#ZERO}.
 */
public interface TimeBudget {
    java.util.Optional<java.time.Duration> remaining();
    boolean expired();
}
