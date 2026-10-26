package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.CleanupContext;

/** Executable body of one registered job cleanup. */
@FunctionalInterface
public interface CleanupBody {
    void execute(CleanupContext context) throws Exception;
}
