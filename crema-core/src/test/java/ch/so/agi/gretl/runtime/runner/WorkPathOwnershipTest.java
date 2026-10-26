package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.OperationHandler;
import ch.so.agi.gretl.runtime.api.OperationType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkPathOwnershipTest {
    @TempDir
    Path directory;

    @Test
    void cachedTaskContextRejectsReplacementTaskDirectory() {
        JobOutcome outcome = run(context -> {
            Path task = context.workPath("anchor").getParent();
            Files.move(task, directory.resolve("original-task"));
            Files.createDirectory(task);
            Files.writeString(task.resolve("sentinel"), "keep");

            assertThrows(IOException.class, () -> context.workPath("new-parent/output"));
            assertFalse(Files.exists(task.resolve("new-parent")));
            assertEquals("keep", Files.readString(task.resolve("sentinel")));
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void cachedTaskContextRejectsReplacementRunRootEvenIfTaskDirectoryIsUnchanged() {
        JobOutcome outcome = run(context -> {
            Path task = context.workPath("anchor").getParent();
            Path run = task.getParent();
            Path original = directory.resolve("original-run");
            Files.move(run, original);
            Files.createDirectory(run);
            Files.move(original.resolve(task.getFileName()), task);
            Files.writeString(run.resolve("sentinel"), "keep");

            assertThrows(IOException.class, () -> context.workPath("new-parent/output"));
            assertFalse(Files.exists(task.resolve("new-parent")));
            assertEquals("keep", Files.readString(run.resolve("sentinel")));
            return "checked";
        });
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("owner").orElseThrow().status());
        assertTrue(outcome.workRetained(), "Retention must refuse the replacement run root");
    }

    @Test
    void cachedOperationContextRejectsReplacementInvocationDirectory() {
        JobOutcome outcome = runOperation((request, context) -> {
            Path invocation = context.workPath("anchor").getParent();
            Files.move(invocation, directory.resolve("original-invocation"));
            Files.createDirectory(invocation);
            Files.writeString(invocation.resolve("sentinel"), "keep");

            assertThrows(IOException.class, () -> context.workPath("new-parent/output"));
            assertFalse(Files.exists(invocation.resolve("new-parent")));
            assertEquals("keep", Files.readString(invocation.resolve("sentinel")));
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void cachedOperationContextRejectsReplacementTaskAncestorEvenIfInvocationIsUnchanged() {
        JobOutcome outcome = runOperation((request, context) -> {
            Path invocation = context.workPath("anchor").getParent();
            Path task = invocation.getParent();
            Path original = directory.resolve("original-task");
            Files.move(task, original);
            Files.createDirectory(task);
            Files.move(original.resolve(invocation.getFileName()), invocation);
            Files.writeString(task.resolve("sentinel"), "keep");

            assertThrows(IOException.class, () -> context.workPath("new-parent/output"));
            assertFalse(Files.exists(invocation.resolve("new-parent")));
            assertEquals("keep", Files.readString(task.resolve("sentinel")));
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void nestedParentSymlinkCannotCreateDirectoriesOutsideTheWorkspace() throws Exception {
        Path external = Files.createDirectory(directory.resolve("external"));
        Files.writeString(external.resolve("sentinel"), "keep");
        JobOutcome outcome = run(context -> {
            Path task = context.workPath("anchor").getParent();
            Files.createSymbolicLink(task.resolve("link"), external);

            assertThrows(IOException.class, () -> context.workPath("link/new-parent/output"));
            assertFalse(Files.exists(external.resolve("new-parent")));
            assertEquals("keep", Files.readString(external.resolve("sentinel")));
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void outputSymlinkIsRejectedWithoutChangingItsTarget() throws Exception {
        Path external = Files.writeString(directory.resolve("external.txt"), "keep");
        JobOutcome outcome = run(context -> {
            Path task = context.workPath("anchor").getParent();
            Files.createSymbolicLink(task.resolve("output"), external);

            assertThrows(IOException.class, () -> context.workPath("output"));
            assertEquals("keep", Files.readString(external));
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @Test
    void newNestedParentsArePrivateAndExistingParentPermissionsAreUnchanged() {
        JobOutcome outcome = run(context -> {
            Path task = context.workPath("anchor").getParent();
            Path existing = Files.createDirectory(task.resolve("existing"));
            boolean posix = Files.getFileStore(existing).supportsFileAttributeView("posix");
            if (posix) {
                Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Path output = context.workPath("existing/new/nested/output");
            assertFalse(Files.exists(output));
            if (posix) {
                assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(existing)));
                assertEquals("rwx------", PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(output.getParent())));
                assertEquals("rwx------", PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(output.getParent().getParent())));
            }
            return "checked";
        });
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    private JobOutcome runOperation(OperationHandler<String, String> handler) {
        OperationType<String, String> operation = new OperationType<>(
                "test.work-path", "WorkPath", "Workspace ownership probe", String.class, String.class,
                () -> () -> "request", () -> handler, List.of(), List.of(), "workPath probe");
        return run(context -> context.call(operation, "request"));
    }

    private JobOutcome run(TaskBody<Object> body) {
        JobDefinition definition = RunnerTestSupport.definition(directory, builder -> {
            builder.register(RunnerTestSupport.task("owner").body(body).build());
            builder.defaults("owner");
        });
        return new SequentialJobRunner().run(definition, RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(directory, new FakeMonotonicClock(), new RecordingEventSink())
                        .keepWork(KeepWork.ALWAYS).build());
    }
}
