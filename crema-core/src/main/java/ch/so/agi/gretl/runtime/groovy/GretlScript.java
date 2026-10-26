package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskRef;
import ch.so.agi.gretl.runtime.runner.JobDefinition;
import ch.so.agi.gretl.runtime.runner.ParameterDefinition;
import ch.so.agi.gretl.runtime.runner.ParameterValueType;
import ch.so.agi.gretl.runtime.runner.TaskCondition;
import ch.so.agi.gretl.runtime.runner.TaskLink;
import ch.so.agi.gretl.runtime.runner.TaskRegistration;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.Script;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Groovy 6 script base class for the canonical GRETL job surface. */
public abstract class GretlScript extends Script {
    private static final Set<String> TASK_OPTIONS = Set.of(
            "dependsOn", "after", "description", "when", "timeout");
    private static final Set<String> PARAMETER_OPTIONS = Set.of(
            "type", "required", "description", "defaultValue");

    public GretlScript() {
        super();
    }

    public GretlScript(Binding binding) {
        super(binding);
    }

    public void parameter(Map<String, ?> options, String name) {
        JobDslModel model = model();
        Map<String, Object> normalized = options(options, PARAMETER_OPTIONS, "parameter " + name);
        String description = text(required(normalized, "description", "Parameter " + name),
                "description");
        if (description.isBlank()) {
            throw dsl("INVALID_PARAMETER_DECLARATION",
                    msg("error.groovy.parameterDescription", name), null);
        }
        ParameterValueType type = ParameterValueType.parse(
                optionalText(normalized.get("type"), "type"));
        boolean required = booleanOption(normalized, "required", false);
        Object defaultValue = normalized.get("defaultValue");
        ParameterDefinition definition = new ParameterDefinition(
                name, type, description, required, Optional.ofNullable(defaultValue));
        model.builder().registerParameter(definition);
    }

    public Object parameter(String name) {
        throw dsl("TOPLEVEL_PARAMETER_READ",
                msg("error.groovy.toplevelParameterRead", name), name);
    }

    public <C, R> TaskRef<R> task(String id, OperationType<C, R> type, Closure<?> configure) {
        return task(Map.of(), id, type, configure);
    }

    public <C, R> TaskRef<R> task(
            Map<String, ?> options, String id, OperationType<C, R> type, Closure<?> configure) {
        JobDslModel model = model();
        if (id == null || id.isBlank()) {
            throw dsl("INVALID_TASK_ID", msg("error.groovy.taskNameEmpty"), null);
        }
        if (type == null) {
            throw dsl("UNKNOWN_OPERATION",
                    msg("error.groovy.taskUnknownType", id), id);
        }
        if (configure == null) {
            throw dsl("MISSING_CONFIGURATION",
                    msg("error.groovy.taskConfiguration", id), id);
        }
        OperationType<?, ?> registered = model.environment().registry().requireId(type.id());
        if (registered != type) {
            throw dsl("UNKNOWN_OPERATION",
                    msg("error.groovy.taskTypeNotLoaded", type.id()), id);
        }
        Map<String, Object> normalized = options(options, TASK_OPTIONS, "task " + id);
        TaskRegistration.Builder<R> registration = TaskRegistration.builder(
                        id, type.resultType())
                .description(description(normalized, id))
                .dependsOn(links(normalized.get("dependsOn"), "dependsOn", id))
                .after(links(normalized.get("after"), "after", id))
                .when(condition(normalized.get("when"), id))
                .timeout(duration(normalized.get("timeout"), id))
                .body(GroovyTaskAdapter.typedBody(type, configure, model))
                .operation(type)
                .source(runnerSource(SourceLocations.current(model.scriptFile())));
        TaskRef<R> ref = model.builder().register(registration.build());
        model.registerTask(id, ref, type);
        return ref;
    }

    public TaskRef<Object> action(String id, Closure<?> body) {
        return action(Map.of(), id, body);
    }

    public TaskRef<Object> action(Map<String, ?> options, String id, Closure<?> body) {
        JobDslModel model = model();
        if (id == null || id.isBlank()) {
            throw dsl("INVALID_TASK_ID", msg("error.groovy.actionNameEmpty"), null);
        }
        if (body == null) {
            throw dsl("MISSING_ACTION_BODY", msg("error.groovy.actionBody", id), id);
        }
        Map<String, Object> normalized = options(options, TASK_OPTIONS, "action " + id);
        TaskRegistration.Builder<Object> registration = TaskRegistration.builder(id, Object.class)
                .description(description(normalized, id))
                .dependsOn(links(normalized.get("dependsOn"), "dependsOn", id))
                .after(links(normalized.get("after"), "after", id))
                .when(condition(normalized.get("when"), id))
                .timeout(duration(normalized.get("timeout"), id))
                .body(GroovyTaskAdapter.actionBody(body, model))
                .source(runnerSource(SourceLocations.current(model.scriptFile())));
        TaskRef<Object> ref = model.builder().register(registration.build());
        model.registerTask(id, ref, null);
        return ref;
    }

    public void defaults(Object... taskNamesOrRefs) {
        JobDslModel model = model();
        List<String> names = new ArrayList<>();
        for (Object value : taskNamesOrRefs) {
            if (value instanceof TaskRef<?> ref) {
                names.add(model.builder().defaultName(ref));
            } else if (value instanceof CharSequence text) {
                names.add(text.toString());
            } else {
                throw dsl("INVALID_DEFAULT_TARGET",
                        "defaults akzeptiert Tasknamen oder TaskRefs, nicht "
                                + (value == null ? "null" : value.getClass().getName()), null);
            }
        }
        if (!names.isEmpty()) {
            model.builder().defaults(names.toArray(String[]::new));
        }
    }

    public void cleanup(String name, Closure<?> body) {
        JobDslModel model = model();
        if (body == null) {
            throw dsl("MISSING_CLEANUP_BODY",
                    msg("error.groovy.cleanupBody", name), name);
        }
        model.builder().cleanup(name, GroovyTaskAdapter.cleanupBody(body, model),
                runnerSource(SourceLocations.current(model.scriptFile())));
    }

    @Override
    public void println(Object value) {
        JobDslModel model = model();
        model.userMessages().println(String.valueOf(value));
    }

    public void println() {
        println("");
    }

    public JobDefinition freeze() {
        JobDslModel model = model();
        model.freeze();
        return model.definition();
    }

    JobDslModel model() {
        Object value = getBinding() == null ? null
                : getBinding().getVariable(JobDslModel.BINDING_NAME);
        if (!(value instanceof JobDslModel model)) {
            throw new IllegalStateException("GretlScript is not attached to a JobDslModel");
        }
        return model;
    }

    private Map<String, Object> options(
            Map<String, ?> options, Set<String> allowed, String context) {
        if (options == null) {
            throw new GroovyDslException("INVALID_OPTIONS",
                    msg("error.groovy.optionsRequired", context), null, null, null, List.of(), null);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : options.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                throw new GroovyDslException("UNKNOWN_OPTION",
                        msg("error.groovy.unknownOption", key, context),
                        null, key, null, List.copyOf(allowed), null);
            }
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private Object required(Map<String, Object> options, String key, String context) {
        if (!options.containsKey(key) || options.get(key) == null) {
            throw new GroovyDslException("MISSING_OPTION",
                    msg("error.groovy.missingOption", context, key),
                    null, key, null, List.of(key), null);
        }
        return options.get(key);
    }

    private String description(Map<String, Object> options, String taskId) {
        String value = optionalText(options.get("description"), "description");
        if (value != null && value.isBlank()) {
            throw new GroovyDslException("INVALID_OPTION",
                    msg("error.groovy.descriptionEmpty"),
                    taskId, "description", null, List.of(), null);
        }
        return value;
    }

    private String optionalText(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof CharSequence text)) {
            throw new GroovyDslException("INVALID_OPTION_TYPE",
                    msg("error.groovy.optionText", field),
                    null, field, null, List.of(), null);
        }
        return text.toString();
    }

    private String text(Object value, String field) {
        return optionalText(value, field);
    }

    private boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new GroovyDslException("INVALID_OPTION_TYPE",
                    msg("error.groovy.optionBoolean", key),
                    null, key, null, List.of(), null);
        }
        return booleanValue;
    }

    private TaskCondition condition(Object value, String taskId) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Closure<?> closure)) {
            throw new GroovyDslException("INVALID_OPTION_TYPE",
                    msg("error.groovy.optionWhenClosure"),
                    taskId, "when", null, List.of(), null);
        }
        return GroovyTaskAdapter.condition(closure, model());
    }

    private Duration duration(Object value, String taskId) {
        if (value == null) {
            return null;
        }
        Duration duration;
        if (value instanceof Duration direct) {
            duration = direct;
        } else if (value instanceof CharSequence text) {
            try {
                duration = Duration.parse(text.toString());
            } catch (RuntimeException exception) {
                throw new GroovyDslException("INVALID_TIMEOUT",
                        msg("error.groovy.timeoutInvalid", text),
                        taskId, "timeout", null, List.of(), exception);
            }
        } else {
            throw new GroovyDslException("INVALID_TIMEOUT",
                    msg("error.groovy.timeoutType"),
                    taskId, "timeout", null, List.of(), null);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new GroovyDslException("INVALID_TIMEOUT",
                    msg("error.groovy.timeoutPositive"), taskId, "timeout", null,
                    List.of(), null);
        }
        return duration;
    }

    private List<TaskLink> links(Object value, String option, String taskId) {
        if (value == null) {
            return List.of();
        }
        List<?> values;
        if (value instanceof TaskRef<?> || value instanceof CharSequence) {
            values = List.of(value);
        } else if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(copy::add);
            values = copy;
        } else if (value instanceof Object[] array) {
            values = Arrays.asList(array);
        } else {
            throw new GroovyDslException("INVALID_OPTION_TYPE",
                    msg("error.groovy.optionLinks", option),
                    taskId, option, null, List.of(), null);
        }
        List<TaskLink> links = new ArrayList<>();
        for (Object element : values) {
            if (element instanceof TaskRef<?> ref) {
                links.add(TaskLink.of(ref));
            } else if (element instanceof CharSequence text) {
                links.add(TaskLink.named(text.toString()));
            } else {
                throw new GroovyDslException("INVALID_OPTION_TYPE",
                        msg("error.groovy.optionLinkElement", option, element),
                        taskId, option, null, List.of(), null);
            }
        }
        return links;
    }

    private String msg(String key, Object... arguments) {
        return Messages.format(model().environment().locale(), key, arguments);
    }

    private static ch.so.agi.gretl.runtime.runner.SourceLocation runnerSource(
            SourceLocation source) {
        return new ch.so.agi.gretl.runtime.runner.SourceLocation(
                source.file(), source.line(), source.column());
    }

    private GroovyDslException dsl(String code, String message, String taskId) {
        return new GroovyDslException(code, message, taskId, null,
                null, List.of(), null);
    }

}
