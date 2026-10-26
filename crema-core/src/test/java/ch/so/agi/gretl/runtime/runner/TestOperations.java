package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ConfigBuilder;
import ch.so.agi.gretl.runtime.api.OperationHandler;
import ch.so.agi.gretl.runtime.api.OperationType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Small independent Java operations used by M1 tests; not product tasks. */
final class TestOperations {
    private TestOperations() {
    }

    static OperationType<String, String> echo(String id, AtomicInteger handlers) {
        return operation(id, String.class, "echo", handlers,
                (request, context) -> request);
    }

    static OperationType<String, Void> voidOperation(String id, AtomicInteger handlers) {
        return operation(id, Void.class, "void", handlers,
                (request, context) -> null);
    }

    static OperationType<String, String> nullResult(String id, AtomicInteger handlers) {
        return operation(id, String.class, "null", handlers,
                (request, context) -> null);
    }

    static OperationType<String, String> failing(String id, AtomicInteger handlers) {
        return operation(id, String.class, "fail", handlers,
                (request, context) -> {
                    throw new IllegalStateException("operation failed");
                });
    }

    static OperationType<String, String> failingWithCause(String id, AtomicInteger handlers) {
        return operation(id, String.class, "fail-cause", handlers,
                (request, context) -> {
                    throw new IllegalStateException("operation failed",
                            new IOException("root cause"));
                });
    }

    static OperationType<String, String> nullHandler(String id) {
        return new OperationType<>(
                id, "null-handler", "Operation ohne Handler", String.class, String.class,
                () -> new ConfigBuilder<String>() {
                    @Override
                    public String build() {
                        return "built";
                    }
                },
                () -> null,
                List.of(), List.of(), "task('probe', 'Test') { }");
    }

    static OperationType<String, String> closeError(String id, AtomicInteger handlers) {
        return operation(id, String.class, "close-error", handlers,
                (request, context) -> {
                    context.resources().manage(() -> {
                        throw new IOException("close-boom");
                    });
                    return "value-before-close-error";
                });
    }

    private static <R> OperationType<String, R> operation(
            String id, Class<R> resultType, String symbol, AtomicInteger handlers,
            OperationHandler<String, R> handler) {
        return new OperationType<>(
                id, symbol, "Testoperation " + id, String.class, resultType,
                () -> new ConfigBuilder<String>() {
                    @Override
                    public String build() {
                        return "built";
                    }
                },
                () -> {
                    handlers.incrementAndGet();
                    return handler;
                },
                List.of(), List.of(),
                "task('probe', 'Test') { }");
    }
}
