package ch.so.agi.gretl.runtime.runner;

/** Production monotonic clock. */
public enum SystemMonotonicClock implements MonotonicClock {
    INSTANCE;

    @Override
    public long ticks() {
        return System.nanoTime();
    }
}
