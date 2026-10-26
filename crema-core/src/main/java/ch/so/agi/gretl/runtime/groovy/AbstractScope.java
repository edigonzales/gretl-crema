package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;

import ch.so.agi.gretl.runtime.api.CancellationToken;
import ch.so.agi.gretl.runtime.api.Diagnostic;
import ch.so.agi.gretl.runtime.api.Progress;
import ch.so.agi.gretl.runtime.api.ResourceScope;
import ch.so.agi.gretl.runtime.api.SecretValue;
import ch.so.agi.gretl.runtime.api.TaskLogger;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingPropertyException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Common helper surface for action and typed-task configuration scopes. */
abstract class AbstractScope extends GroovyObjectSupport {
    protected final JobDslModel model;
    protected final GroovyContextFacade facade;

    AbstractScope(JobDslModel model, GroovyContextFacade facade) {
        this.model = model;
        this.facade = facade;
    }

    public Object parameter(String name) {
        return facade.parameter(name);
    }

    public <T> T parameter(String name, Class<T> type) {
        return facade.parameter(name, type);
    }

    public Path file(String path) {
        return facade.file(path);
    }

    public Path path(String path) {
        return facade.path(path);
    }

    public Path workPath(String path) throws IOException {
        return facade.workPath(path);
    }

    public Map<String, Object> config(String alias) {
        return facade.config(alias);
    }

    public SecretValue secret(String alias) {
        return facade.secret(alias);
    }

    public String sensitive(String value) {
        return facade.sensitive(value);
    }

    public TaskLogger log() {
        return facade.log();
    }

    public void report(Diagnostic diagnostic) {
        facade.report(diagnostic);
    }

    public void progress(Progress progress) {
        facade.progress(progress);
    }

    public CancellationToken cancellation() {
        return facade.cancellation();
    }

    public TimeBudget budget() {
        return facade.budget();
    }

    public ResourceScope resources() {
        return facade.resources();
    }

    public void println(Object value) {
        facade.println(value);
    }

    @Override
    public Object getProperty(String name) {
        if ("ctx".equals(name)) {
            return facade;
        }
        if ("metaClass".equals(name)) {
            return super.getMetaClass();
        }
        if ("class".equals(name)) {
            return getClass();
        }
        var symbol = model.operationSymbol(name);
        if (symbol.isPresent()) {
            return symbol.get();
        }
        throw new MissingPropertyException(name, getClass());
    }

    @Override
    public void setProperty(String name, Object value) {
        throw error("READ_ONLY_SCOPE",
                Messages.format(model.environment().locale(),
                        "error.groovy.scopeReadOnly", name),
                name, java.util.List.of(), null);
    }

    protected GroovyDslException error(
            String code, String message, String field, java.util.List<String> available,
            Throwable cause) {
        String taskId = facade.taskContext() == null ? null
                : facade.taskContext().identity().taskId().orElse(null);
        return new GroovyDslException(code, message, taskId, field,
                SourceLocations.current(model.scriptFile()), available, cause);
    }
}
