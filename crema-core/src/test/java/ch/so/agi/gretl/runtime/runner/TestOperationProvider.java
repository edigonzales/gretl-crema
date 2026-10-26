package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.OperationProvider;
import ch.so.agi.gretl.runtime.api.OperationType;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** ServiceLoader fixture for the M1 registry test. */
public final class TestOperationProvider implements OperationProvider {
    private static final AtomicInteger HANDLERS = new AtomicInteger();

    @Override
    public Collection<OperationType<?, ?>> operations() {
        return List.of(TestOperations.echo("test.echo", HANDLERS));
    }
}
