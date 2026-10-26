package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.runtime.api.ConfigBuilder;
import ch.so.agi.gretl.runtime.api.InputField;
import ch.so.agi.gretl.runtime.api.OperationContext;
import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;
import ch.so.agi.gretl.runtime.api.TaskRef;
import groovy.lang.Closure;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fresh delegate for one typed-task configuration or one {@code ctx.run}
 * configuration. Field values are converted immediately, stored once, and
 * applied to the fresh builder only after the closure has run.
 */
final class TaskConfigurationScope<C, R> extends AbstractScope {
    private static final Set<String> RESERVED = Set.of(
            "ctx", "delegate", "owner", "thisObject", "metaClass", "class");

    private final OperationType<C, R> type;
    private final Closure<?> configure;
    private final List<InputField<C, ?>> fields;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final String taskId;
    private final java.util.Locale locale;

    TaskConfigurationScope(
            OperationType<C, R> type, Closure<?> configure, JobDslModel model,
            TaskContext outer, OperationContext inner) {
        super(model, new GroovyContextFacade(model, inner, outer));
        this.type = type;
        this.configure = configure;
        this.fields = type.inputs();
        this.taskId = outer.identity().taskId().orElse(type.id());
        this.locale = inner.locale();
        for (InputField<C, ?> field : fields) {
            field.defaultValue().ifPresent(value -> {
                try {
                    values.put(field.name(), convert(field, value));
                } catch (RuntimeException exception) {
                    throw error("INVALID_CONFIGURATION_VALUE",
                            Messages.format(locale, "error.groovy.defaultConvert", field.name()),
                            field.name(), fieldNames(), exception);
                }
            });
        }
    }

    C buildRequest() throws Exception {
        GroovyTaskAdapter.evaluateClosure(configure, this, facade, model,
                "task configuration", taskId);
        for (InputField<C, ?> field : fields) {
            if (field.required() && values.get(field.name()) == null) {
                throw error("MISSING_REQUIRED_FIELD",
                        Messages.format(locale, "error.groovy.missingRequired", field.name()),
                        field.name(), fieldNames(), null);
            }
        }
        ConfigBuilder<C> builder = type.builderFactory().get();
        if (builder == null) {
            throw error("NULL_CONFIG_BUILDER",
                    Messages.format(locale, "error.groovy.nullBuilder", type.symbol()),
                    null, fieldNames(), null);
        }
        for (InputField<C, ?> field : fields) {
            if (!values.containsKey(field.name())) {
                continue;
            }
            try {
                assign(field, builder, values.get(field.name()));
            } catch (RuntimeException exception) {
                throw error("INVALID_CONFIGURATION_VALUE",
                        Messages.format(locale, "error.groovy.assignField", field.name()),
                        field.name(), fieldNames(), exception);
            }
        }
        C request = builder.build();
        if (request == null) {
            throw error("NULL_REQUEST",
                    Messages.format(locale, "error.groovy.nullRequest", type.symbol()),
                    null, fieldNames(), null);
        }
        return request;
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
        if (RESERVED.contains(name)) {
            throw error("RESERVED_CONFIGURATION_FIELD",
                    Messages.format(locale, "error.groovy.reservedRead", name),
                    name, fieldNames(), null);
        }
        if (field(name) != null) {
            return values.get(name);
        }
        var symbol = model.operationSymbol(name);
        if (symbol.isPresent()) {
            return symbol.get();
        }
        throw error("UNKNOWN_CONFIGURATION_FIELD",
                Messages.format(locale, "error.unknownConfigurationField",
                        name, type.symbol()),
                name, fieldNames(), null);
    }

    @Override
    public void setProperty(String name, Object value) {
        if (RESERVED.contains(name)) {
            throw error("RESERVED_CONFIGURATION_FIELD",
                    Messages.format(locale, "error.groovy.reservedWrite", name),
                    name, fieldNames(), null);
        }
        InputField<C, ?> field = field(name);
        if (field == null) {
            throw error("UNKNOWN_CONFIGURATION_FIELD",
                    Messages.format(locale, "error.unknownConfigurationField",
                            name, type.symbol()),
                    name, fieldNames(), null);
        }
        Object converted;
        try {
            converted = convert(field, value);
        } catch (RuntimeException exception) {
            throw error("INVALID_CONFIGURATION_VALUE",
                    Messages.format(locale, "error.groovy.invalidValue", name),
                    name, fieldNames(), exception);
        }
        values.put(name, converted);
    }

    @Override
    public Object invokeMethod(String name, Object args) {
        if (field(name) != null) {
            throw error("FIELD_ASSIGNMENT_REQUIRED",
                    Messages.format(locale, "error.groovy.fieldAssignment", name),
                    name, fieldNames(), null);
        }
        return super.invokeMethod(name, args);
    }

    public Object result(String taskName) {
        return facade.result(taskName);
    }

    public java.util.Optional<Object> resultOptional(String taskName) {
        return facade.resultOptional(taskName);
    }

    public <T> T result(TaskRef<T> ref) {
        return facade.result(ref);
    }

    public <T> java.util.Optional<T> resultOptional(TaskRef<T> ref) {
        return facade.resultOptional(ref);
    }

    public void finishJob(String reason) {
        facade.finishJob(reason);
    }

    private InputField<C, ?> field(String name) {
        return fields.stream().filter(field -> field.name().equals(name))
                .findFirst().orElse(null);
    }

    private List<String> fieldNames() {
        return fields.stream().map(InputField::name).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(InputField<?, ?> field, Object value) {
        return ((InputField) field).convert().apply(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assign(InputField<?, ?> field, ConfigBuilder<?> builder, Object value) {
        ((InputField) field).assign().accept((ConfigBuilder) builder, value);
    }

    @Override
    protected GroovyDslException error(
            String code, String message, String field, List<String> available,
            Throwable cause) {
        return new GroovyDslException(code, message, taskId, field,
                SourceLocations.current(model.scriptFile()), available, cause);
    }
}
