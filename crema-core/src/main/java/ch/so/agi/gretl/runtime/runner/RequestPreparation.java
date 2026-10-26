package ch.so.agi.gretl.runtime.runner;

/** Builds a request inside a fresh invocation frame (phase configuration). */
@FunctionalInterface
public interface RequestPreparation<C> {
    C prepare(InvocationFrame frame) throws Exception;
}
