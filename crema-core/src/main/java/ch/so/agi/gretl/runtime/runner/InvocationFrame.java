package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Internal lifetime object for one synchronous operation invocation. */
final class InvocationFrame {
    private final String invocationId;
    private final TaskFrame parent;
    private final OperationType<?, ?> operation;
    private final java.nio.file.Path invocationDirectory;
    private final CloseStack closeStack;
    private final OperationContextImpl context;
    private final Instant startedAt;
    private final long startTicks;
    private String phase = "configuration";

    InvocationFrame(String invocationId, TaskFrame parent, OperationType<?, ?> operation,
                    java.nio.file.Path invocationDirectory, CloseStack closeStack,
                    OperationContextImpl context, Instant startedAt, long startTicks) {
        this.invocationId = invocationId;
        this.parent = parent;
        this.operation = operation;
        this.invocationDirectory = invocationDirectory;
        this.closeStack = closeStack;
        this.context = context;
        this.startedAt = startedAt;
        this.startTicks = startTicks;
    }

    String invocationId() {
        return invocationId;
    }

    TaskFrame parent() {
        return parent;
    }

    OperationType<?, ?> operation() {
        return operation;
    }

    java.nio.file.Path invocationDirectory() {
        return invocationDirectory;
    }

    CloseStack closeStack() {
        return closeStack;
    }

    OperationContextImpl context() {
        return context;
    }

    String phase() {
        return phase;
    }

    void phase(String phase) {
        this.phase = phase;
    }

    List<Throwable> closeResources() {
        return context.closeResources();
    }

    InvocationOutcome outcome(InvocationStatus status, FailureInfo failure,
                              List<FailureInfo> secondary, Optional<ch.so.agi.gretl.runtime.api.CancelReason> cancelReason, Instant finishedAt, long endTicks) {
        long duration = Math.max(0, endTicks - startTicks) / 1_000_000L;
        return new InvocationOutcome(invocationId, parent.definition().id(), operation.id(),
                status, phase, Optional.of(startedAt), Optional.of(finishedAt),
                duration, Optional.ofNullable(failure), secondary, cancelReason);
    }
}
