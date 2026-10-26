package ch.so.agi.gretl.runtime.runner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns run/task/invocation directories. Retention traverses only the run root
 * created by this manager.
 */
public final class WorkspaceManager {
    private final Path workRoot;

    public WorkspaceManager(Path workRoot) {
        this.workRoot = Objects.requireNonNull(workRoot, "workRoot").toAbsolutePath().normalize();
    }

    public Workspace openRun(UUID runId) throws IOException {
        Objects.requireNonNull(runId, "runId");
        Path root = workRoot.resolve(runId.toString()).normalize();
        if (!root.startsWith(workRoot)) {
            throw new IOException("run workspace escapes work root");
        }
        createPrivateDirectories(workRoot);
        createPrivateDirectory(root);
        return new Workspace(this, root);
    }

    public boolean applyRetention(Workspace workspace, RunStatus status, KeepWork mode) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
        if (workspace.owner != this) {
            throw new IOException("workspace belongs to another manager");
        }
        workspace.verifyOwnedDirectory(workspace.runRoot());
        if (mode.retain(status)) {
            return true;
        }
        deleteRecursively(workspace.runRoot());
        return false;
    }

    public Path workRoot() {
        return workRoot;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void createPrivateDirectories(Path path) throws IOException {
        try {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException unsupported) {
            Files.createDirectories(path);
        }
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException unsupported) {
            Files.createDirectory(path);
        }
    }

    /** One run root with deterministic task and invocation subdirectories. */
    public static final class Workspace {
        private final WorkspaceManager owner;
        private final Path runRoot;
        private final Map<Path, DirectoryIdentity> ownedDirectories = new HashMap<>();

        private Workspace(WorkspaceManager owner, Path runRoot) throws IOException {
            this.owner = owner;
            this.runRoot = runRoot;
            ownedDirectories.put(runRoot, DirectoryIdentity.read(runRoot));
        }

        public Path runRoot() {
            return runRoot;
        }

        public Path taskDirectory(int registrationIndex) throws IOException {
            Path task = runRoot.resolve("task-%06d".formatted(registrationIndex)).normalize();
            if (!task.startsWith(runRoot)) {
                throw new IOException("task workspace escapes run root");
            }
            return openOwnedDirectory(task);
        }

        public Path cleanupDirectory(int cleanupIndex) throws IOException {
            Path cleanup = runRoot.resolve("cleanup-%03d".formatted(cleanupIndex)).normalize();
            if (!cleanup.startsWith(runRoot)) {
                throw new IOException("cleanup workspace escapes run root");
            }
            return openOwnedDirectory(cleanup);
        }

        public Path invocationDirectory(int registrationIndex, String invocationId) throws IOException {
            if (invocationId == null || !invocationId.matches("[A-Za-z0-9_.-]+")
                    || invocationId.equals(".") || invocationId.equals("..")) {
                throw new IOException("unsafe invocation id");
            }
            Path task = taskDirectory(registrationIndex);
            Path invocation = task.resolve(invocationId).normalize();
            if (!invocation.startsWith(task)) {
                throw new IOException("invocation workspace escapes task root");
            }
            return openOwnedDirectory(invocation);
        }

        void prepareWorkPath(Path workBase, Path path) throws IOException {
            if (!workBase.startsWith(runRoot) || !path.startsWith(workBase)) {
                throw new IOException("work path escapes owned workspace");
            }
            verifyOwnedDirectory(runRoot);
            for (Path owned = workBase; !owned.equals(runRoot); owned = owned.getParent()) {
                verifyOwnedDirectory(owned);
            }
            Path parent = path.getParent();
            if (parent != null && parent.startsWith(workBase)) {
                Path current = workBase;
                for (Path segment : workBase.relativize(parent)) {
                    if (segment.toString().isEmpty()) {
                        continue;
                    }
                    current = current.resolve(segment);
                    try {
                        createPrivateDirectory(current);
                    } catch (FileAlreadyExistsException existing) {
                        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("work path parent is not a directory: " + current, existing);
                        }
                    }
                }
            }
            if (Files.isSymbolicLink(path)) {
                throw new IOException("work path must not be a symbolic link: " + path);
            }
        }

        private Path openOwnedDirectory(Path directory) throws IOException {
            verifyOwnedDirectory(runRoot);
            if (ownedDirectories.containsKey(directory)) {
                verifyOwnedDirectory(directory);
            } else {
                createPrivateDirectory(directory);
                ownedDirectories.put(directory, DirectoryIdentity.read(directory));
            }
            return directory;
        }

        private void verifyOwnedDirectory(Path directory) throws IOException {
            DirectoryIdentity expected = ownedDirectories.get(directory);
            if (expected == null || !expected.equals(DirectoryIdentity.read(directory))) {
                throw new IOException("owned workspace directory was replaced: " + directory);
            }
        }
    }

    private record DirectoryIdentity(Object fileKey, FileTime creationTime) {
        static DirectoryIdentity read(Path directory) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("workspace path is not an owned directory: " + directory);
            }
            return new DirectoryIdentity(attributes.fileKey(), attributes.creationTime());
        }
    }
}
