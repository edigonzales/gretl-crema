package ch.so.agi.gretl.runtime.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Adapted for the consolidated experiment: API package stays JDK-only;
 * the core artifact intentionally also contains the Groovy adapter.
 */
class ApiIsolationTest {

    private static final List<String> FORBIDDEN = List.of(
            "groovy/", "org/slf4j/", "ch/qos/logback/", "io/opentelemetry/",
            "org/geotools/", "org/locationtech/jts/", "com/vividsolutions/",
            "ch/interlis/", "org/postgresql/", "org/gradle/");

    @Test
    void compiledApiDoesNotReferenceForbiddenLibraries() throws IOException {
        Path classes = Path.of("target", "classes", "ch", "so", "agi", "gretl", "runtime", "api");
        assertTrue(Files.isDirectory(classes), "compiled API classes exist at " + classes);
        try (Stream<Path> files = Files.walk(classes)) {
            List<Path> classFiles = files
                    .filter(path -> path.toString().endsWith(".class"))
                    .toList();
            assertFalse(classFiles.isEmpty(), "API classes were compiled");
            for (Path classFile : classFiles) {
                String constantPool = new String(
                        Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN) {
                    assertFalse(constantPool.contains(forbidden),
                            () -> classFile + " references forbidden dependency " + forbidden);
                }
            }
        }
    }

    @Test
    void forbiddenLibrariesAreNotLoadableFromTheApiTestClasspath() {
        for (String className : List.of(
                "org.slf4j.Logger",
                "ch.qos.logback.classic.LoggerContext",
                "org.geotools.data.DataStore",
                "ch.interlis.ili2c.metamodel.TransferDescription",
                "org.postgresql.Driver",
                "org.gradle.api.Project")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(className, false, getClass().getClassLoader()),
                    className + " must not be on the API module classpath");
        }
    }
}
