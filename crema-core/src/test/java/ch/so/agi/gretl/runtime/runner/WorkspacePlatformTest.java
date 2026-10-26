package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePlatformTest {
    @TempDir Path root;
    @Test void configuredSymlinkRootPreservesTargetMetadataAndForeignSibling() throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxrwx---"));
        PosixFileAttributes before = Files.readAttributes(target, PosixFileAttributes.class);
        Path link = Files.createSymbolicLink(root.resolve("configured-root"), target);
        Path foreign = Files.writeString(target.resolve("foreign"), "unchanged");
        var manager = new WorkspaceManager(link);
        var workspace = manager.openRun(UUID.randomUUID());
        assertFalse(manager.applyRetention(workspace, RunStatus.SUCCEEDED, KeepWork.NEVER));
        PosixFileAttributes after = Files.readAttributes(target, PosixFileAttributes.class);
        assertEquals(before.permissions(), after.permissions());
        assertEquals(before.owner(), after.owner()); assertEquals(before.group(), after.group());
        assertEquals("unchanged", Files.readString(foreign));
    }

    @Test void deniedPosixRootIsNotRepaired() throws Exception {
        assertFalse(System.getProperty("user.name").equals("root"), "Permission qualification requires an unprivileged user");
        Path denied = Files.createDirectory(root.resolve("denied"));
        var mode = PosixFilePermissions.fromString("r-x------");
        Files.setPosixFilePermissions(denied, mode);
        try {
            assertThrows(java.io.IOException.class, () -> new WorkspaceManager(denied).openRun(UUID.randomUUID()));
            assertEquals(mode, Files.getPosixFilePermissions(denied));
        } finally { Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("rwx------")); }
    }

    @Test void nonPosixZipFileSystemUsesPortableCreationAndStillRejectsCollisions() throws Exception {
        URI uri = URI.create("jar:" + root.resolve("portable.zip").toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            assertFalse(fs.supportedFileAttributeViews().contains("posix"));
            WorkspaceManager manager = new WorkspaceManager(fs.getPath("/work"));
            UUID id = UUID.randomUUID();
            var workspace = manager.openRun(id);
            Files.writeString(workspace.taskDirectory(0).resolve("data"), "data");
            assertThrows(java.io.IOException.class, () -> manager.openRun(id));
            assertFalse(manager.applyRetention(workspace, RunStatus.SUCCEEDED, KeepWork.NEVER));
            assertTrue(Files.isDirectory(fs.getPath("/work")));
        }
    }
}
