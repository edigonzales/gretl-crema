package ch.so.agi.gretl.crema.compiler;

import ch.so.agi.gretl.runtime.groovy.CompilerSettings;
import groovy.lang.GroovyClassLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;

/** Adapted from GRETL JobCompiler: class generation only, never script execution. */
public final class JobCompiler {
    public void compile(Path source, Path output, boolean plain) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Source not found: " + source);
        CompilerConfiguration configuration = CompilerSettings.create();
        if (plain) configuration.setScriptBaseClass("groovy.lang.Script");
        try (var loader = new GroovyClassLoader(getClass().getClassLoader(), configuration)) {
            var unit = new CompilationUnit(configuration, null, loader);
            unit.addSource(source.toFile());
            unit.compile(Phases.CLASS_GENERATION);
            String entryClass = null;
            for (var module : unit.getAST().getModules()) {
                for (var node : module.getClasses()) {
                    if (!plain && node.isScript()) {
                        if (entryClass != null) throw new IOException("Multiple script entry classes");
                        entryClass = node.getName();
                    }
                }
            }
            if (!plain && entryClass == null) throw new IOException("No GRETL script entry class");
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (entryClass != null) attributes.putValue("Gretl-Job-Class", entryClass);
            attributes.putValue("Gretl-Source-Name", source.getFileName().toString());
            var classes = new TreeMap<String, byte[]>();
            for (var compiled : unit.getClasses()) {
                classes.put(compiled.getName().replace('.', '/') + ".class", compiled.getBytes());
            }
            Path target = output.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".job-", ".jar");
            try {
                try (var jar = new JarOutputStream(Files.newOutputStream(temporary), manifest)) {
                    for (var entry : classes.entrySet()) {
                        var jarEntry = new JarEntry(entry.getKey());
                        jarEntry.setTime(0);
                        jar.putNextEntry(jarEntry);
                        jar.write(entry.getValue());
                        jar.closeEntry();
                    }
                }
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
