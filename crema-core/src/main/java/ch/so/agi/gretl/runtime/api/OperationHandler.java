package ch.so.agi.gretl.runtime.api;

/**
 * Synchronous implementation of one reusable operation.
 *
 * @param <C> request type
 * @param <R> result type; {@link Void} for an operation without a value
 */
@FunctionalInterface
public interface OperationHandler<C, R> {
    R execute(C request, OperationContext context) throws Exception;
}
