package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;
import ch.so.agi.gretl.runtime.runner.CleanupBody;
import ch.so.agi.gretl.runtime.runner.OperationCaller;
import ch.so.agi.gretl.runtime.runner.TaskBody;
import ch.so.agi.gretl.runtime.runner.TaskCondition;
import groovy.lang.Closure;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.util.List;

/** Binds stored closures to fresh scopes only at task or invocation time. */
public final class GroovyTaskAdapter {
    private GroovyTaskAdapter() {
    }

    public static <C, R> TaskBody<R> typedBody(
            OperationType<C, R> type, Closure<?> configure, JobDslModel model) {
        return context -> {
            if (!(context instanceof OperationCaller caller)) {
                throw new GroovyDslException("NO_OPERATION_CALLER",
                        msg(model, "error.groovy.noCallerTyped"));
            }
            String taskId = context.identity().taskId().orElse(type.id());
            return caller.invoke(type, (invocationContext, taskContext) ->
                    new TaskConfigurationScope<>(type, configure, model, taskContext,
                            invocationContext).buildRequest());
        };
    }

    public static TaskBody<Object> actionBody(Closure<?> body, JobDslModel model) {
        return context -> {
            ActionScope scope = new ActionScope(context, model);
            String taskId = context.identity().taskId().orElse(null);
            return evaluateClosure(body, scope, scope.contextFacade(), model,
                    "action", taskId);
        };
    }

    public static TaskCondition condition(Closure<?> condition, JobDslModel model) {
        return context -> {
            ActionScope scope = new ActionScope(context, model);
            String taskId = context.identity().taskId().orElse(null);
            Object value = evaluateClosure(condition, scope, scope.contextFacade(), model,
                    "when", taskId);
            if (!(value instanceof Boolean booleanValue)) {
                throw new GroovyDslException("CONDITION_NOT_BOOLEAN",
                        msg(model, "error.groovy.conditionNotBoolean",
                                value == null ? "null" : value.getClass().getName()),
                        taskId, null, SourceLocations.current(model.scriptFile()),
                        List.of(), null);
            }
            return booleanValue;
        };
    }

    public static CleanupBody cleanupBody(Closure<?> body, JobDslModel model) {
        return context -> {
            CleanupScope scope = new CleanupScope(context, model);
            evaluateClosure(body, scope, context, model, "cleanup", null);
        };
    }

    static Object evaluateClosure(
            Closure<?> closure, Object delegate, Object parameter,
            JobDslModel model, String role, String taskId) throws Exception {
        if (closure == null) {
            throw new GroovyDslException("MISSING_CLOSURE",
                    msg(model, "error.groovy.missingClosure", role), taskId, null,
                    SourceLocations.current(model.scriptFile()), List.of(), null);
        }
        Closure<?> copy = (Closure<?>) closure.rehydrate(
                delegate, closure.getOwner(), closure.getThisObject());
        copy.setResolveStrategy(Closure.DELEGATE_ONLY);
        try {
            int parameters = copy.getMaximumNumberOfParameters();
            if (parameters == 0) {
                return copy.call();
            }
            if (parameters == 1) {
                return copy.call(parameter);
            }
            throw new GroovyDslException("CLOSURE_ARITY",
                    msg(model, "error.groovy.closureArity", role, parameters),
                    taskId, null, SourceLocations.current(model.scriptFile()),
                    List.of(), null);
        } catch (GroovyDslException exception) {
            throw withSource(exception, model);
        } catch (MissingPropertyException exception) {
            throw new GroovyDslException("UNKNOWN_PROPERTY",
                    msg(model, "error.groovy.unknownProperty",
                            exception.getProperty(), role),
                    taskId, exception.getProperty(), SourceLocations.from(exception, model.scriptFile()),
                    List.of(), exception);
        } catch (MissingMethodException exception) {
            throw new GroovyDslException("UNKNOWN_HELPER",
                    msg(model, "error.groovy.unknownHelper",
                            exception.getMethod(), role),
                    taskId, exception.getMethod(), SourceLocations.from(exception, model.scriptFile()),
                    List.of(), exception);
        }
    }

    private static String msg(JobDslModel model, String key, Object... arguments) {
        return Messages.format(model.environment().locale(), key, arguments);
    }

    private static GroovyDslException withSource(GroovyDslException exception, JobDslModel model) {
        if (exception.source() != null && exception.source().line() > 0) {
            return exception;
        }
        return new GroovyDslException(exception.code(), exception.getMessage(),
                exception.taskId(), exception.fieldName(),
                SourceLocations.from(exception, model.scriptFile()),
                exception.available(), exception);
    }
}
