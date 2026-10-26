package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.CancellationToken;
import ch.so.agi.gretl.runtime.api.ContextView;
import ch.so.agi.gretl.runtime.api.Diagnostic;
import ch.so.agi.gretl.runtime.api.OperationContext;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.Progress;
import ch.so.agi.gretl.runtime.api.ResourceScope;
import ch.so.agi.gretl.runtime.api.SecretValue;
import ch.so.agi.gretl.runtime.api.TaskContext;
import ch.so.agi.gretl.runtime.api.TaskRef;
import ch.so.agi.gretl.runtime.api.TaskLogger;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import ch.so.agi.gretl.runtime.runner.OperationCaller;
import groovy.lang.Closure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Groovy context facade. It uses the inner invocation context for paths,
 * logging and resources, the outer task context for result and finish helpers,
 * and never exposes a runner frame.
 */
public final class GroovyContextFacade {
    private final JobDslModel model;
    private final OperationContext inner;
    private final TaskContext outer;

    GroovyContextFacade(JobDslModel model, OperationContext inner, TaskContext outer) {
        this.model = model;
        this.inner = java.util.Objects.requireNonNull(inner, "inner");
        this.outer = outer;
    }

    public Object parameter(String name) {
        return inner.parameter(name);
    }

    public <T> T parameter(String name, Class<T> type) {
        return inner.parameter(name, type);
    }

    public Path file(String path) {
        return inner.file(path);
    }

    public Path path(String path) {
        return inner.path(path);
    }

    public Path workPath(String path) throws IOException {
        return inner.workPath(path);
    }

    public Map<String, Object> config(String alias) {
        return inner.config(alias);
    }

    public SecretValue secret(String alias) {
        return inner.secret(alias);
    }

    public String sensitive(String value) {
        return inner.sensitive(value);
    }

    public TaskLogger log() {
        return inner.log();
    }

    public void report(Diagnostic diagnostic) {
        inner.report(diagnostic);
    }

    public void progress(Progress progress) {
        inner.progress(progress);
    }

    public CancellationToken cancellation() {
        return inner.cancellation();
    }

    public TimeBudget budget() {
        return inner.budget();
    }

    public ResourceScope resources() {
        return inner.resources();
    }

    public void println(Object value) {
        inner.log().info(String.valueOf(value));
    }

    public Object result(String taskName) {
        requireTaskContext("result");
        TaskRef<?> ref = model.resolveTask(taskName, taskId());
        Object raw = outer.result(ref);
        return wrapResult(taskName, raw);
    }

    public Optional<Object> resultOptional(String taskName) {
        requireTaskContext("resultOptional");
        TaskRef<?> ref = model.resolveTask(taskName, taskId());
        Optional<?> raw = outer.resultOptional(ref);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(wrapResult(taskName, raw.orElseThrow()));
    }

    public <R> R result(TaskRef<R> ref) {
        requireTaskContext("result(TaskRef)");
        return outer.result(ref);
    }

    public <R> Optional<R> resultOptional(TaskRef<R> ref) {
        requireTaskContext("resultOptional(TaskRef)");
        return outer.resultOptional(ref);
    }

    public <C, R> R run(OperationType<C, R> type, Closure<?> configure) throws Exception {
        requireTaskContext("ctx.run");
        if (type == null) {
            throw new GroovyDslException("UNKNOWN_OPERATION",
                    msg("error.groovy.contextRunType"));
        }
        if (configure == null) {
            throw new GroovyDslException("MISSING_CONFIGURATION",
                    msg("error.groovy.contextRunConfiguration", type.symbol()));
        }
        if (!(outer instanceof OperationCaller caller)) {
            throw new GroovyDslException("NO_OPERATION_CALLER",
                    msg("error.groovy.contextRunCaller"));
        }
        return caller.invoke(type, (invocationContext, taskContext) ->
                new TaskConfigurationScope<>(type, configure, model, taskContext,
                        invocationContext).buildRequest());
    }

    public void finishJob(String reason) {
        requireTaskContext("finishJob");
        outer.finishJob(reason);
    }

    ContextView view() {
        return inner;
    }

    TaskContext taskContext() {
        return outer;
    }

    private void requireTaskContext(String operation) {
        if (outer == null) {
            throw new GroovyDslException("TASK_CONTEXT_REQUIRED",
                    msg("error.groovy.taskContextRequired", operation));
        }
    }

    private String msg(String key, Object... arguments) {
        return Messages.format(model.environment().locale(), key, arguments);
    }

    private String taskId() {
        return outer == null ? null : outer.identity().taskId().orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object wrapResult(String taskName, Object raw) {
        OperationType<?, ?> operation = model.taskOperation(taskName).orElse(null);
        if (operation == null) {
            return raw;
        }
        return new ResultView(operation, raw, model.environment().locale());
    }
}
