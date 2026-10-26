package ch.so.agi.gretl.runtime.runner;

import java.nio.file.Files;

/** Reuses the actual assertions in a Linux JDK process under an unprivileged UID. */
public final class PosixQualification {
    private PosixQualification() { }
    public static void main(String[] args) throws Exception {
        var test = new WorkspacePlatformTest();
        test.root = Files.createTempDirectory("gretl-posix-qualification-");
        test.deniedPosixRootIsNotRepaired();
        test.configuredSymlinkRootPreservesTargetMetadataAndForeignSibling();
        test.nonPosixZipFileSystemUsesPortableCreationAndStillRejectsCollisions();
        var ownership = new WorkspaceOwnershipTest();
        ownership.directory = Files.createTempDirectory("gretl-owned-qualification-");
        ownership.refusesExistingRunDirectoryWithoutChangingPermissionsOrContents();
        ownership.createsPrivateOwnedDirectoriesAndLeavesExistingWorkRootAlone();
        ownership.refusesForeignSubdirectoryInsideOwnedRun();
        ownership.refusesRunSymlinkAndDoesNotTouchItsTarget();
        ownership.retentionRejectsAnotherManagersWorkspace();
        ownership.retentionRejectsReplacedRunDirectory();
        System.out.println("PASS: denied root, owner/group/mode, symlink root, ZIP filesystem; os="
                + System.getProperty("os.name") + " user=" + System.getProperty("user.name")
                + " java=" + Runtime.version());
    }
}
