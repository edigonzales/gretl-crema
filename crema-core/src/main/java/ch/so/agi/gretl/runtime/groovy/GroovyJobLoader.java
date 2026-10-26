package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.runner.DefinitionException;
import ch.so.agi.gretl.runtime.runner.JobDefinition;
import ch.so.agi.gretl.runtime.runner.JobDefinitionBuilder;
import ch.so.agi.gretl.runtime.runner.ParameterException;
import groovy.lang.Binding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads one precompiled Groovy job with a fresh binding. Top-level
 * code registers definitions; task closures are not evaluated here.
 */
public final class GroovyJobLoader {

    public JobDefinition loadCompiled(String entryClass, Path scriptLabel,
                                      DefinitionEnvironment environment) {
        return loadCompiled(entryClass, scriptLabel, environment,
                GroovyJobLoader.class.getClassLoader());
    }

    /**
     * Instantiates a precompiled script class. The packager uses the same
     * path; no source compilation or script execution happens at package time.
     */
    public JobDefinition loadCompiled(String entryClass, Path scriptLabel,
                                      DefinitionEnvironment environment,
                                      ClassLoader classLoader) {
        java.util.Objects.requireNonNull(entryClass, "entryClass");
        java.util.Objects.requireNonNull(scriptLabel, "scriptLabel");
        java.util.Objects.requireNonNull(environment, "environment");
        java.util.Objects.requireNonNull(classLoader, "classLoader");
        Path source = scriptLabel.toAbsolutePath().normalize();
        try {
            Class<?> type = Class.forName(entryClass, true, classLoader);
            if (!GretlScript.class.isAssignableFrom(type)) {
                throw new GroovyDslException("INVALID_SCRIPT_BASE",
                        Messages.get(environment.locale(), "error.groovy.invalidCompiledBase"),
                        null, null, SourceLocations.fallback(source), List.of(), null);
            }
            JobDslModel model = new JobDslModel(environment,
                    new JobDefinitionBuilder(environment.jobRoot()), source);
            Binding binding = new Binding();
            binding.setVariable(JobDslModel.BINDING_NAME, model);
            for (OperationType<?, ?> operation : environment.registry().all()) {
                binding.setVariable(operation.symbol(), operation);
            }
            GretlScript instance = (GretlScript) type.getDeclaredConstructor().newInstance();
            instance.setBinding(binding);
            instance.run();
            return instance.freeze();
        } catch (GroovyDslException exception) {
            throw exception;
        } catch (DefinitionException | ParameterException exception) {
            throw exception;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new GroovyDslException("JOB_LOAD_FAILED",
                    Messages.format(environment.locale(), "error.groovy.compiledLoadFailed",
                            describe(throwable)),
                    null, null, SourceLocations.fallback(source), List.of(), throwable);
        }
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
