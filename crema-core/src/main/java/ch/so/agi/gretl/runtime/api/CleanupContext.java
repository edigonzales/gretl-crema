package ch.so.agi.gretl.runtime.api;

/**
 * Restricted context of a job cleanup action. It allows logging, parameters,
 * config and known workspace paths, plus local resource release. It does not
 * allow starting tasks or {@code ctx.run}.
 */
public interface CleanupContext extends ContextView {
    ResourceScope resources();
    TimeBudget budget();
}
