package ch.so.agi.gretl.runtime.api;

/**
 * Creates a complete, fachlich valid request for one operation invocation.
 *
 * <p>A fresh builder is supplied for every invocation. {@link #build()} must not
 * perform network or domain work; it may validate cross-field invariants and
 * reject incomplete requests.</p>
 *
 * @param <C> concrete request type
 */
public interface ConfigBuilder<C> {
    C build();
}
