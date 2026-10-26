package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ResourceScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * LIFO close stack for one task or invocation. Every registered resource is
 * attempted exactly once; real close errors are returned in order.
 */
public final class CloseStack implements ResourceScope {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Set<AutoCloseable> identities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    @Override
    public <T extends AutoCloseable> T manage(T resource) {
        java.util.Objects.requireNonNull(resource, "resource");
        if (closed) {
            throw new IllegalStateException("resource scope is already closed");
        }
        if (!identities.add(resource)) {
            throw new IllegalArgumentException("resource identity already registered");
        }
        resources.add(resource);
        return resource;
    }

    public List<Throwable> closeAll() {
        return closeAll(() -> { });
    }

    /** Observe control at release boundaries, while still attempting every close. */
    List<Throwable> closeAll(Runnable observeControl) {
        return closeAll(observeControl, failure -> failure);
    }

    List<Throwable> closeAll(Runnable observeControl,
                             java.util.function.UnaryOperator<Throwable> observeFailure) {
        if (closed) {
            return List.of();
        }
        closed = true;
        List<Throwable> failures = new ArrayList<>();
        observe(observeControl, failures);
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Throwable failure) {
            FailureArbiter.rethrowFatal(failure);
                failures.add(observeFailure.apply(failure));
            }
            observe(observeControl, failures);
        }
        resources.clear();
        identities.clear();
        return List.copyOf(failures);
    }

    private static void observe(Runnable observer, List<Throwable> failures) {
        try {
            observer.run();
        } catch (ch.so.agi.gretl.runtime.api.TaskCancelledException cancellation) {
            if (failures.stream().noneMatch(
                    ch.so.agi.gretl.runtime.api.TaskCancelledException.class::isInstance)) {
                failures.add(cancellation);
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int size() {
        return resources.size();
    }
}
