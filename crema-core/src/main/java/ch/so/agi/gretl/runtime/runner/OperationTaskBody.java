package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;

/**
 * Java typed-task body: it gives the request preparation to the invocation
 * invoker, so configuration failures already carry the invocation identity.
 * This is not a Groovy task adapter.
 */
public final class OperationTaskBody<C, R> implements TaskBody<R> {
    private final OperationType<C, R> operation;
    private final RequestPreparation<C> preparation;

    public OperationTaskBody(OperationType<C, R> operation, RequestPreparation<C> preparation) {
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
        this.preparation = java.util.Objects.requireNonNull(preparation, "preparation");
    }

    @Override
    public R execute(TaskContext context) throws Exception {
        if (!(context instanceof TaskContextImpl taskContext)) {
            throw new IllegalStateException("OperationTaskBody requires the runner task context");
        }
        return taskContext.invokeOperation(operation, preparation);
    }

    public OperationType<C, R> operation() {
        return operation;
    }
}
