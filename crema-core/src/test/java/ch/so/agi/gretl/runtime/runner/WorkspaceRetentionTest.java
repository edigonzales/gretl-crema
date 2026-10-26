package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRetentionTest {

    @TempDir
    Path tempDir;

    @Test
    void neverRemovesSuccessfulAndFailedWorkspaces() throws Exception {
        UUID runId = UUID.randomUUID();
        JobOutcome outcome = run(runId, KeepWork.NEVER, false);
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertFalse(outcome.workRetained());
        assertFalse(Files.exists(workRoot(runId)));
    }

    @Test
    void alwaysRetainsSuccessfulWorkspaces() throws Exception {
        UUID runId = UUID.randomUUID();
        JobOutcome outcome = run(runId, KeepWork.ALWAYS, false);
        assertTrue(outcome.workRetained());
        assertTrue(Files.isDirectory(workRoot(runId)));
    }

    @Test
    void onFailureRemovesSuccessfulWorkspaceButRetainsFailureWorkspace() throws Exception {
        UUID successId = UUID.randomUUID();
        JobOutcome success = run(successId, KeepWork.ON_FAILURE, false);
        assertFalse(success.workRetained());
        assertFalse(Files.exists(workRoot(successId)));

        UUID failureId = UUID.randomUUID();
        JobOutcome failure = run(failureId, KeepWork.ON_FAILURE, true);
        assertEquals(RunStatus.FAILED, failure.status());
        assertTrue(failure.workRetained());
        assertTrue(Files.isDirectory(workRoot(failureId)));
    }

    @Test
    void workspaceDirectoriesAreCreatedUnderTheOwnedRunRoot() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(tempDir.resolve("work"));
        UUID runId = UUID.randomUUID();
        WorkspaceManager.Workspace workspace = manager.openRun(runId);
        Path task = workspace.taskDirectory(3);
        Path invocation = workspace.invocationDirectory(3, "i000001");
        Path cleanup = workspace.cleanupDirectory(0);

        assertTrue(Files.isDirectory(task));
        assertTrue(Files.isDirectory(invocation));
        assertTrue(invocation.startsWith(task));
        assertTrue(Files.isDirectory(cleanup));
        assertTrue(cleanup.startsWith(workspace.runRoot()));
        assertEquals(workRoot(runId), workspace.runRoot());
    }

    private JobOutcome run(UUID runId, KeepWork keepWork, boolean fail) {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> {
                if (fail) {
                    throw new IllegalStateException("failure");
                }
                return "value";
            }).build());
            builder.defaults("a");
        });
        RunInput input = RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink())
                .runId(runId)
                .keepWork(keepWork)
                .build();
        return new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), input);
    }

    private Path workRoot(UUID runId) {
        return tempDir.resolve("work").resolve(runId.toString());
    }
}
