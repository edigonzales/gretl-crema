package ch.so.agi.gretl.runtime.api;

/**
 * Source of complete operation definitions.
 *
 * <p>The method is read-only and returns a finite collection. It must not open
 * domain connections, start engines or read secrets. The service discovery file
 * is {@code META-INF/services/ch.so.agi.gretl.runtime.api.OperationProvider}.</p>
 */
public interface OperationProvider {
    java.util.Collection<OperationType<?, ?>> operations();
}
