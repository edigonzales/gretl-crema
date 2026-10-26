package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.runner.DefinitionException;
import ch.so.agi.gretl.runtime.runner.JobDefinition;
import ch.so.agi.gretl.runtime.runner.JobDefinitionBuilder;
import ch.so.agi.gretl.runtime.runner.ParameterException;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads one Groovy job with a fresh compiler, class loader and binding. Top-level
 * code registers definitions; task closures are not evaluated here.
 */
public final class SourceJobLoader {

    public JobDefinition load(Path scriptFile, DefinitionEnvironment environment) {
        java.util.Objects.requireNonNull(scriptFile, "scriptFile");
        java.util.Objects.requireNonNull(environment, "environment");
        Path script = scriptFile.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(script)) {
                throw new GroovyDslException("JOB_NOT_FOUND",
                        Messages.format(environment.locale(), "error.groovy.jobNotFound", script),
                        null, null, SourceLocations.fallback(script), List.of(), null);
            }
            JobDslModel model = new JobDslModel(environment,
                    new JobDefinitionBuilder(environment.jobRoot()), script);
            var configuration = CompilerSettings.create();
            GroovyClassLoader classLoader = new GroovyClassLoader(
                    SourceJobLoader.class.getClassLoader(), configuration);
            Binding binding = new Binding();
            binding.setVariable(JobDslModel.BINDING_NAME, model);
            for (OperationType<?, ?> operation : environment.registry().all()) {
                binding.setVariable(operation.symbol(), operation);
            }
            GroovyShell shell = new GroovyShell(classLoader, binding, configuration);
            Script scriptInstance = shell.parse(script.toFile());
            if (!(scriptInstance instanceof GretlScript gretlScript)) {
                throw new GroovyDslException("INVALID_SCRIPT_BASE",
                        Messages.get(environment.locale(), "error.groovy.invalidScriptBase"),
                        null, null, SourceLocations.fallback(script), List.of(), null);
            }
            scriptInstance.run();
            return gretlScript.freeze();
        } catch (GroovyDslException exception) {
            throw withSource(exception, script);
        } catch (DefinitionException | ParameterException exception) {
            throw exception;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new GroovyDslException("JOB_LOAD_FAILED",
                    Messages.format(environment.locale(), "error.groovy.loadFailed",
                            describe(throwable)),
                    null, null, SourceLocations.from(throwable, script),
                    List.of(), throwable);
        }
    }

    private static GroovyDslException withSource(GroovyDslException exception, Path script) {
        if (exception.source() != null && exception.source().line() > 0) {
            return exception;
        }
        return new GroovyDslException(exception.code(), exception.getMessage(),
                exception.taskId(), exception.fieldName(),
                SourceLocations.from(exception, script), exception.available(), exception);
    }

    private static String describe(Throwable throwable) {
        if (throwable instanceof IOException) {
            return throwable.getMessage();
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getName() : message;
    }
}
