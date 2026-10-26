package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskContext;

/** Executable body of one task. This interface contains no Groovy type. */
@FunctionalInterface
public interface TaskBody<R> {
    R execute(TaskContext context) throws Exception;
}
