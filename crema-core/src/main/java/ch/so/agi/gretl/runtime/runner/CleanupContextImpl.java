package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.CleanupContext;
import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.CancellationToken;
import ch.so.agi.gretl.runtime.api.TaskCancelledException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Restricted job-cleanup context; it cannot call tasks or finish the job. */
final class CleanupContextImpl extends BaseContext implements CleanupContext {
    private final DirectoryPreparation prepareDirectory;
    private boolean directoryReady;

    CleanupContextImpl(RunEnvironment environment, Path workBase, CloseStack closeStack,
                       ch.so.agi.gretl.runtime.api.TimeBudget budget, String cleanupName,
                       DirectoryPreparation prepareDirectory) {
        super(environment, environment.identity(null, null), workBase, closeStack, budget,
                Optional.empty(), Map.of("cleanup.name", cleanupName));
        this.prepareDirectory = prepareDirectory;
    }

    @Override
    public Path workPath(String relativePath) throws IOException {
        checkWork();
        if (!directoryReady) {
            prepareDirectory.prepare();
            directoryReady = true;
        }
        return super.workPath(relativePath);
    }

    @Override
    void checkExecution() {
        checkCancellation();
    }

    @Override
    void checkCancellation() {
        if (budget().expired()) {
            throw new TaskCancelledException(CancelReason.TIMEOUT);
        }
    }

    @Override
    Throwable observeCloseFailure(Throwable failure) {
        return failure;
    }

    @Override
    Optional<CancelReason> currentCancellationReason() {
        return budget().expired() ? Optional.of(CancelReason.TIMEOUT) : Optional.empty();
    }

    @FunctionalInterface
    interface DirectoryPreparation {
        void prepare() throws IOException;
    }
}
