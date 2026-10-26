package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.runtime.api.TaskContext;

/** Scope for ordinary action code and {@code when} predicates. */
final class ActionScope extends AbstractScope {
    private final TaskContext context;

    ActionScope(TaskContext context, JobDslModel model) {
        super(model, new GroovyContextFacade(model, context, context));
        this.context = context;
    }

    public Object result(String taskName) {
        return facade.result(taskName);
    }

    public java.util.Optional<Object> resultOptional(String taskName) {
        return facade.resultOptional(taskName);
    }

    public <R> R result(ch.so.agi.gretl.runtime.api.TaskRef<R> ref) {
        return facade.result(ref);
    }

    public <R> java.util.Optional<R> resultOptional(ch.so.agi.gretl.runtime.api.TaskRef<R> ref) {
        return facade.resultOptional(ref);
    }

    public void finishJob(String reason) {
        facade.finishJob(reason);
    }

    TaskContext context() {
        return context;
    }

    GroovyContextFacade contextFacade() {
        return facade;
    }
}
