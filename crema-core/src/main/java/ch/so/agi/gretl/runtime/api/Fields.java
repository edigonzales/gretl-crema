package ch.so.agi.gretl.runtime.api;

/**
 * Small typed metadata factories for operation providers.
 *
 * <p>These factories build the same {@link InputField} and {@link OutputField}
 * values that providers may also construct directly. They do not scan beans or
 * annotations; the builder cast is explicit and checked.</p>
 */
public final class Fields {
    private Fields() {}

    public static <C, B extends ConfigBuilder<C>, V> InputField<C, V> input(
        String name, String valueType, String description, boolean required,
        java.util.Optional<V> defaultValue, java.util.List<String> allowedValues,
        String example, Class<B> builderClass,
        java.util.function.Function<Object, V> converter,
        java.util.function.BiConsumer<B, V> setter
    ) {
        java.util.Objects.requireNonNull(builderClass, "builderClass");
        java.util.Objects.requireNonNull(setter, "setter");
        return new InputField<>(name, valueType, description, required,
            defaultValue, allowedValues, example, converter,
            (builder, value) -> setter.accept(builderClass.cast(builder), value));
    }

    public static <C, B extends ConfigBuilder<C>> InputField<C, java.nio.file.Path>
    requiredPath(String name, String description, Class<B> builderClass,
                 java.util.function.BiConsumer<B, java.nio.file.Path> setter) {
        return input(name, "path", description, true, java.util.Optional.empty(),
            java.util.List.of(), "file('input.xtf')", builderClass,
            value -> {
                if (value instanceof java.nio.file.Path path) return path;
                throw new IllegalArgumentException(name + " erwartet einen Path");
            }, setter);
    }

    public static <R, V> OutputField<R, V> output(
        String name, String valueType, String description, boolean nullable,
        java.util.function.Function<R, V> reader
    ) { return new OutputField<>(name, valueType, description, nullable, reader); }
}
