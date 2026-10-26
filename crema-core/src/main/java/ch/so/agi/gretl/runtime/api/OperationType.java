package ch.so.agi.gretl.runtime.api;

/**
 * Complete, immutable definition of one operation.
 *
 * <p>Builder and handler factories return a fresh instance for every invocation.
 * The definition carries the metadata used by help, the DSL and result views;
 * it must not hold credentials, open streams or connections.</p>
 *
 * @param <C> request type
 * @param <R> result type
 */
public record OperationType<C, R>(
    String id, String symbol, String description, Class<C> requestType,
    Class<R> resultType,
    java.util.function.Supplier<? extends ConfigBuilder<C>> builderFactory,
    java.util.function.Supplier<? extends OperationHandler<C, R>> handlerFactory,
    java.util.List<InputField<C, ?>> inputs,
    java.util.List<OutputField<R, ?>> outputs, String example
) {
    public OperationType {
        id = ApiChecks.text(id, "id");
        symbol = ApiChecks.text(symbol, "symbol");
        description = ApiChecks.text(description, "description");
        java.util.Objects.requireNonNull(requestType, "requestType");
        java.util.Objects.requireNonNull(resultType, "resultType");
        java.util.Objects.requireNonNull(builderFactory, "builderFactory");
        java.util.Objects.requireNonNull(handlerFactory, "handlerFactory");
        inputs = java.util.List.copyOf(inputs);
        outputs = java.util.List.copyOf(outputs);
        ApiChecks.unique(inputs.stream().map(InputField::name).toList());
        ApiChecks.unique(outputs.stream().map(OutputField::name).toList());
        example = ApiChecks.text(example, "example");
        if (resultType == Void.class && !outputs.isEmpty())
            throw new IllegalArgumentException("Void-Operation ohne Ausgaenge definieren");
    }
}
