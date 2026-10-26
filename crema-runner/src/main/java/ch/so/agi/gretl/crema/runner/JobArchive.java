package ch.so.agi.gretl.crema.runner;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.Manifest;
import java.util.jar.JarFile;

/** Keeps lazy closure/helper loading alive until all tasks have completed. */
public final class JobArchive implements AutoCloseable {
    private final URLClassLoader loader;
    private final ClassLoader previous;
    private final Manifest manifest;

    public JobArchive(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) { manifest = file.getManifest(); }
        previous = Thread.currentThread().getContextClassLoader();
        loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, JobArchive.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
    }

    public ClassLoader loader() { return loader; }

    public String entryClass() throws IOException {
        String entry = attribute("Gretl-Job-Class");
        if (entry == null || entry.isBlank()) throw new IOException("Missing Gretl-Job-Class in job manifest");
        return entry;
    }

    public String sourceName() {
        String name = attribute("Gretl-Source-Name");
        // A label for diagnostics, never a source or resource path to load.
        return name == null ? "job.groovy" : Path.of(name).getFileName().toString();
    }

    private String attribute(String key) {
        return manifest == null ? null : manifest.getMainAttributes().getValue(key);
    }

    @Override public void close() throws IOException {
        Thread.currentThread().setContextClassLoader(previous);
        loader.close();
    }
}
