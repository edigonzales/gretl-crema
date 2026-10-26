package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskContext;

/** Predicate evaluated immediately before the task body. */
@FunctionalInterface
public interface TaskCondition {
    boolean test(TaskContext context) throws Exception;
}
