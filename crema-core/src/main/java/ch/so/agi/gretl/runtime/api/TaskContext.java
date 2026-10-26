package ch.so.agi.gretl.runtime.api;

/**
 * Context of an action or a named task execution.
 *
 * <p>{@link #result(TaskRef)} reads raw Java values only from a declared direct
 * or transitive data predecessor. {@link #call(OperationType, Object)} performs
 * exactly one synchronous operation call with an already validated request.</p>
 *
 * @param <R> value type of result access
 */
public interface TaskContext extends OperationContext {
    <R> R result(TaskRef<R> task);
    <R> java.util.Optional<R> resultOptional(TaskRef<R> task);
    <C, R> R call(OperationType<C, R> type, C validatedRequest) throws Exception;
    void finishJob(String reason);
}
