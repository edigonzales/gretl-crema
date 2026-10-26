package ch.so.agi.gretl.runtime.api;

/** Read-only cooperative cancellation signal. */
public interface CancellationToken {
    boolean isCancellationRequested();
    java.util.Optional<CancelReason> reason();
    void throwIfCancellationRequested() throws TaskCancelledException;
}
