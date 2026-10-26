package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;
import ch.so.agi.gretl.runtime.api.TaskRef;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Scoped task context with result access and synchronous operation calls. */
final class TaskContextImpl extends BaseContext implements TaskContext, OperationCaller {
    private final TaskRef<?> taskRef;
    private final TaskFrame frame;
    private final ResultStore resultStore;
    private final OperationInvoker invoker;

    TaskContextImpl(RunEnvironment environment, TaskRef<?> taskRef, TaskFrame frame,
                    Path workBase, CloseStack closeStack,
                    ch.so.agi.gretl.runtime.api.TimeBudget budget,
                    ResultStore resultStore, OperationInvoker invoker) {
        super(environment, environment.identity(taskRef.id(), null),
                workBase, closeStack, budget, Optional.empty(), Map.of());
        this.taskRef = taskRef;
        this.frame = frame;
        this.resultStore = resultStore;
        this.invoker = invoker;
    }

    @Override
    public <R> R result(TaskRef<R> task) {
        checkDataAccess();
        return resultStore.require(taskRef, task);
    }

    @Override
    public <R> Optional<R> resultOptional(TaskRef<R> task) {
        checkDataAccess();
        return resultStore.optional(taskRef, task);
    }

    @Override
    public <C, R> R call(OperationType<C, R> type, C validatedRequest) throws Exception {
        checkWork();
        checkExecution();
        if (validatedRequest == null) {
            throw new IllegalArgumentException("validatedRequest must not be null");
        }
        if (!type.requestType().isInstance(validatedRequest)) {
            throw new IllegalArgumentException("request is not an instance of "
                    + type.requestType().getName());
        }
        return invokeOperation(type, invocation -> validatedRequest);
    }

    <C, R> R invokeOperation(OperationType<C, R> type, RequestPreparation<C> preparation)
            throws Exception {
        checkWork();
        checkExecution();
        return invoker.invoke(type, preparation, frame);
    }

    @Override
    public <C, R> R invoke(OperationType<C, R> type,
                           OperationCaller.InvocationPreparation<C> preparation)
            throws Exception {
        checkWork();
        checkExecution();
        return invoker.invoke(type, frame -> preparation.prepare(frame.context(), this), frame);
    }

    @Override
    public void finishJob(String reason) {
        checkWork();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("finish reason must not be blank");
        }
        checkExecution();
        environment().control().requestFinish(reason);
        throw new FinishSignal(reason);
    }

    TaskRef<?> taskRef() {
        return taskRef;
    }
}
