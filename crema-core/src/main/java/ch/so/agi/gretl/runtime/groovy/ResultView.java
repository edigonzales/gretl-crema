package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.OutputField;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingPropertyException;
import java.util.List;

/**
 * Read-only Groovy view over explicitly declared OutputFields. No bean/record
 * reflection and no implicit serialization are used.
 */
public final class ResultView<R> extends GroovyObjectSupport {
    private final OperationType<?, R> operation;
    private final R result;
    private final List<OutputField<R, ?>> outputs;
    private final java.util.Locale locale;

    public ResultView(OperationType<?, R> operation, R result) {
        this(operation, result, java.util.Locale.GERMAN);
    }

    @SuppressWarnings("unchecked")
    public ResultView(OperationType<?, R> operation, R result, java.util.Locale locale) {
        this.operation = operation;
        this.result = result;
        this.outputs = (List<OutputField<R, ?>>) (List<?>) operation.outputs();
        this.locale = java.util.Objects.requireNonNull(locale, "locale");
    }

    @Override
    public Object getProperty(String name) {
        if ("metaClass".equals(name)) {
            return super.getMetaClass();
        }
        if ("class".equals(name)) {
            return getClass();
        }
        OutputField<R, ?> field = outputs.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new GroovyDslException(
                        "UNKNOWN_OUTPUT_FIELD",
                        Messages.format(locale, "error.groovy.unknownOutputField",
                                operation.symbol(), name),
                        null, name, SourceLocations.fallback(null),
                        outputs.stream().map(OutputField::name).toList(), null));
        Object value = field.read().apply(result);
        if (value == null && !field.nullable()) {
            throw new GroovyDslException("NULL_OUTPUT_FIELD",
                    Messages.format(locale, "error.groovy.nullOutputField", name),
                    null, name, SourceLocations.fallback(null), List.of(), null);
        }
        return value;
    }

    @Override
    public void setProperty(String name, Object value) {
        throw new GroovyDslException("READ_ONLY_RESULT",
                Messages.format(locale, "error.groovy.readOnlyResult", name));
    }

    @Override
    public Object invokeMethod(String name, Object args) {
        throw new GroovyDslException("READ_ONLY_RESULT",
                Messages.format(locale, "error.groovy.readOnlyResultMethod", name));
    }

    @Override
    public String toString() {
        return "ResultView[" + operation.symbol() + "]";
    }

    /** Groovy can ask for a missing property through MissingPropertyException. */
    @SuppressWarnings("unused")
    private void propertyMissing(String name) {
        throw new MissingPropertyException(name, ResultView.class);
    }
}
