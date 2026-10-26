package ch.so.agi.gretl.runtime.api;

/** Explicit reference to an existing diagnostic artifact. */
public record DiagnosticArtifact(
    String name, java.nio.file.Path path, String mediaType, boolean restricted
) {
    public DiagnosticArtifact {
        name = ApiChecks.text(name, "name");
        java.util.Objects.requireNonNull(path, "path");
        path = path.toAbsolutePath().normalize();
        mediaType = ApiChecks.text(mediaType, "mediaType");
    }
}
