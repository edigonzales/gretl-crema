package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ExecutionIdentity;
import ch.so.agi.gretl.runtime.api.OperationContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Scoped operation context; deliberately has no result or task-call API. */
final class OperationContextImpl extends BaseContext implements OperationContext {
    OperationContextImpl(RunEnvironment environment, ExecutionIdentity identity,
                         Path workBase, CloseStack closeStack,
                         ch.so.agi.gretl.runtime.api.TimeBudget budget,
                         String operationId) {
        super(environment, identity, workBase, closeStack, budget,
                Optional.of(operationId), Map.of("operation.id", operationId));
    }
}
