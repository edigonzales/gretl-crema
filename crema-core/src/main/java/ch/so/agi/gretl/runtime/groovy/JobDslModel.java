package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskRef;
import ch.so.agi.gretl.runtime.runner.JobDefinition;
import ch.so.agi.gretl.runtime.runner.JobDefinitionBuilder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Mutable load-time model shared by GretlScript and the execution scopes. */
final class JobDslModel {
    static final String BINDING_NAME = "__gretlModel";

    private final DefinitionEnvironment environment;
    private final JobDefinitionBuilder builder;
    private final Path scriptFile;
    private final Map<String, TaskRef<?>> taskRefs = new LinkedHashMap<>();
    private final Map<String, OperationType<?, ?>> taskOperations = new LinkedHashMap<>();
    private final Map<String, OperationType<?, ?>> symbols;
    private JobDefinition definition;

    JobDslModel(DefinitionEnvironment environment, JobDefinitionBuilder builder, Path scriptFile) {
        this.environment = environment;
        this.builder = builder;
        this.scriptFile = scriptFile.toAbsolutePath().normalize();
        Map<String, OperationType<?, ?>> symbolMap = new LinkedHashMap<>();
        for (OperationType<?, ?> operation : environment.registry().all()) {
            symbolMap.putIfAbsent(operation.symbol(), operation);
        }
        this.symbols = Collections.unmodifiableMap(symbolMap);
    }

    DefinitionEnvironment environment() {
        return environment;
    }

    JobDefinitionBuilder builder() {
        return builder;
    }

    Path scriptFile() {
        return scriptFile;
    }

    SourceLocation source() {
        return SourceLocations.fallback(scriptFile);
    }

    Map<String, OperationType<?, ?>> symbols() {
        return symbols;
    }

    Optional<OperationType<?, ?>> operationSymbol(String name) {
        return Optional.ofNullable(symbols.get(name));
    }

    void registerTask(String name, TaskRef<?> ref, OperationType<?, ?> operation) {
        taskRefs.put(name, ref);
        if (operation == null) {
            taskOperations.remove(name);
        } else {
            taskOperations.put(name, operation);
        }
    }

    TaskRef<?> resolveTask(String name, String requestingTask) {
        TaskRef<?> ref = taskRefs.get(name);
        if (ref == null) {
            throw new GroovyDslException("UNKNOWN_TASK_NAME",
                    Messages.format(environment.locale(), "error.groovy.unknownTask", name),
                    requestingTask, null,
                    SourceLocations.current(scriptFile), taskNames(), null);
        }
        return ref;
    }

    Optional<OperationType<?, ?>> taskOperation(String name) {
        return Optional.ofNullable(taskOperations.get(name));
    }

    List<String> taskNames() {
        return List.copyOf(new LinkedHashSet<>(taskRefs.keySet()));
    }

    JobDefinition definition() {
        return definition;
    }

    void freeze() {
        if (definition == null) {
            definition = builder.freeze();
        }
    }

    UserMessageSink userMessages() {
        return environment.userMessages();
    }

    Set<String> availableSymbols() {
        return symbols.keySet();
    }
}
