package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationContext;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;

/**
 * Internal Java bridge used by the Groovy adapter (and other language adapters)
 * to perform a typed invocation with late request preparation. It exposes only
 * API context types, not runner frames.
 */
public interface OperationCaller {
    <C, R> R invoke(OperationType<C, R> type, InvocationPreparation<C> preparation)
            throws Exception;

    /**
     * Builds the request inside a fresh invocation frame. The invocation context
     * owns the invocation workspace/log identity; the task context is read-only
     * for result and finish helpers.
     */
    @FunctionalInterface
    interface InvocationPreparation<C> {
        C prepare(OperationContext invocationContext, TaskContext taskContext) throws Exception;
    }
}
