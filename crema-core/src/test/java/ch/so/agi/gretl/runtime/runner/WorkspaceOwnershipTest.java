package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceOwnershipTest {
    @TempDir
    Path directory;

    @Test
    void refusesExistingRunDirectoryWithoutChangingPermissionsOrContents() throws Exception {
        UUID runId = UUID.randomUUID();
        Path root = Files.createDirectory(directory.resolve(runId.toString()));
        Files.writeString(root.resolve("foreign.txt"), "keep");
        boolean posix = Files.getFileStore(root).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertThrows(IOException.class, () -> new WorkspaceManager(directory).openRun(runId));

        assertEquals("keep", Files.readString(root.resolve("foreign.txt")));
        if (posix) {
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(root)));
        }
    }

    @Test
    void createsPrivateOwnedDirectoriesAndLeavesExistingWorkRootAlone() throws Exception {
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        WorkspaceManager.Workspace workspace = new WorkspaceManager(directory).openRun(UUID.randomUUID());
        Path task = workspace.taskDirectory(1);
        Path invocation = workspace.invocationDirectory(1, "i000001");
        Path cleanup = workspace.cleanupDirectory(0);

        assertEquals(task, workspace.taskDirectory(1));
        if (posix) {
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
            for (Path owned : new Path[]{workspace.runRoot(), task, invocation, cleanup}) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(owned)),
                        owned.toString());
            }
        }
    }

    @Test
    void refusesForeignSubdirectoryInsideOwnedRun() throws Exception {
        WorkspaceManager.Workspace workspace = new WorkspaceManager(directory).openRun(UUID.randomUUID());
        Path foreign = Files.createDirectory(workspace.runRoot().resolve("task-000001"));
        Files.writeString(foreign.resolve("sentinel"), "keep");

        assertThrows(IOException.class, () -> workspace.taskDirectory(1));
        assertEquals("keep", Files.readString(foreign.resolve("sentinel")));
    }

    @Test
    void refusesRunSymlinkAndDoesNotTouchItsTarget() throws Exception {
        UUID runId = UUID.randomUUID();
        Path external = Files.createDirectory(directory.resolve("external"));
        Files.writeString(external.resolve("sentinel"), "keep");
        Path link = directory.resolve(runId.toString());
        Files.createSymbolicLink(link, external);

        assertThrows(IOException.class, () -> new WorkspaceManager(directory).openRun(runId));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("keep", Files.readString(external.resolve("sentinel")));
    }

    @Test
    void retentionRejectsAnotherManagersWorkspace() throws Exception {
        WorkspaceManager owner = new WorkspaceManager(directory);
        WorkspaceManager.Workspace workspace = owner.openRun(UUID.randomUUID());
        Files.writeString(workspace.runRoot().resolve("sentinel"), "keep");

        assertThrows(IOException.class, () -> new WorkspaceManager(directory)
                .applyRetention(workspace, RunStatus.SUCCEEDED, KeepWork.NEVER));
        assertEquals("keep", Files.readString(workspace.runRoot().resolve("sentinel")));
    }

    @Test
    void retentionRejectsReplacedRunDirectory() throws Exception {
        WorkspaceManager owner = new WorkspaceManager(directory);
        WorkspaceManager.Workspace workspace = owner.openRun(UUID.randomUUID());
        Files.move(workspace.runRoot(), directory.resolve("original-run"));
        Files.createDirectory(workspace.runRoot());
        Files.writeString(workspace.runRoot().resolve("sentinel"), "keep");

        assertThrows(IOException.class, () -> owner.applyRetention(workspace, RunStatus.SUCCEEDED, KeepWork.NEVER));
        assertEquals("keep", Files.readString(workspace.runRoot().resolve("sentinel")));
    }
}
