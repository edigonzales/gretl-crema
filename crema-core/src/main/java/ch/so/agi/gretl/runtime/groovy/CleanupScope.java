package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.CleanupContext;
import ch.so.agi.gretl.runtime.api.ResourceScope;
import ch.so.agi.gretl.runtime.api.SecretValue;
import ch.so.agi.gretl.runtime.api.TaskLogger;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingPropertyException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Restricted job-cleanup scope: no new tasks, no result, no ctx.run. */
final class CleanupScope extends GroovyObjectSupport {
    private final CleanupContext context;
    private final JobDslModel model;

    CleanupScope(CleanupContext context, JobDslModel model) {
        this.context = context;
        this.model = model;
    }

    public Object parameter(String name) {
        return context.parameter(name);
    }

    public <T> T parameter(String name, Class<T> type) {
        return context.parameter(name, type);
    }

    public Path file(String path) {
        return context.file(path);
    }

    public Path path(String path) {
        return context.path(path);
    }

    public Path workPath(String path) throws IOException {
        return context.workPath(path);
    }

    public Map<String, Object> config(String alias) {
        return context.config(alias);
    }

    public TaskLogger log() {
        return context.log();
    }

    public void report(ch.so.agi.gretl.runtime.api.Diagnostic diagnostic) {
        context.report(diagnostic);
    }

    public ResourceScope resources() {
        return context.resources();
    }

    public TimeBudget budget() {
        return context.budget();
    }

    public void println(Object value) {
        context.log().info(String.valueOf(value));
    }

    @Override
    public Object getProperty(String name) {
        if ("ctx".equals(name)) {
            return context;
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
        throw new GroovyDslException("READ_ONLY_SCOPE",
                Messages.format(model.environment().locale(),
                        "error.groovy.cleanupReadOnly", name),
                null, name, SourceLocations.current(model.scriptFile()), java.util.List.of(), null);
    }

    public SecretValue secret(String alias) {
        throw new GroovyDslException("SECRET_NOT_ALLOWED",
                Messages.get(model.environment().locale(),
                        "error.groovy.cleanupSecret"),
                null, "secret", SourceLocations.current(model.scriptFile()),
                java.util.List.of(), null);
    }
}
