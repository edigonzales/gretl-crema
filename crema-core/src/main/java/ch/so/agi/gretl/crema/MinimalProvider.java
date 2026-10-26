package ch.so.agi.gretl.crema;

import ch.so.agi.gretl.runtime.api.OperationProvider;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.tasks.database.SqlExecutorOperation;
import example.ExampleProvider;
import java.util.ArrayList;
import java.util.Collection;

/** Deliberately fixed catalog for this experiment. */
public final class MinimalProvider implements OperationProvider {
    @Override public Collection<OperationType<?, ?>> operations() {
        var operations = new ArrayList<OperationType<?, ?>>(new ExampleProvider().operations());
        operations.add(SqlExecutorOperation.type());
        return java.util.List.copyOf(operations);
    }
}
