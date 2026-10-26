package ch.so.agi.gretl.runtime.runner;

/** Monotonic tick source used for durations and budgets. */
@FunctionalInterface
public interface MonotonicClock {
    long ticks();
}
