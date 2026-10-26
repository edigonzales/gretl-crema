package ch.so.agi.gretl.runtime.api;

/**
 * One explicitly offered configuration field of an operation.
 *
 * <p>{@code convert} must enforce the same allowed-values restriction that is
 * documented by {@code allowedValues}; the runtime does not infer domain enum
 * conversion from a type name. Defaults use the same converter and setter as
 * explicit user values.</p>
 *
 * @param <C> request builder type
 * @param <V> converted field value type
 */
public record InputField<C, V>(
    String name, String valueType, String description, boolean required,
    java.util.Optional<V> defaultValue, java.util.List<String> allowedValues,
    String example, java.util.function.Function<Object, V> convert,
    java.util.function.BiConsumer<ConfigBuilder<C>, V> assign
) {
    public InputField {
        name = ApiChecks.text(name, "name");
        valueType = ApiChecks.text(valueType, "valueType");
        description = ApiChecks.text(description, "description");
        java.util.Objects.requireNonNull(defaultValue, "defaultValue");
        allowedValues = java.util.List.copyOf(allowedValues);
        example = ApiChecks.text(example, "example");
        java.util.Objects.requireNonNull(convert, "convert");
        java.util.Objects.requireNonNull(assign, "assign");
    }
}
