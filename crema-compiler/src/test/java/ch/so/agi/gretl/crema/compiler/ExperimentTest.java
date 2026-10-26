package ch.so.agi.gretl.crema.compiler;

import ch.so.agi.gretl.crema.runner.Main;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExperimentTest {
    @TempDir Path temporary;

    private Path example() { return Path.of("../examples/demo").toAbsolutePath().normalize(); }
    private String[] args(Path jar, Path root, String year) {
        return new String[]{"run", jar.toString(), "--job-root", root.toString(), "--db", temporary.resolve("db").toString(), "--param", "jahr=" + year};
    }

    @Test void compiledAndSourceJobsAgreeAndSqlStaysExternal() throws Exception {
        Path root = temporary.resolve("job"); Files.createDirectories(root);
        for (String file : new String[]{"job.groovy", "schema.sql", "input.txt"}) Files.copy(example().resolve(file), root.resolve(file));
        Path jar = temporary.resolve("job.jar");
        new JobCompiler().compile(root.resolve("job.groovy"), jar, false);
        try (var archive = new JarFile(jar.toFile())) {
            assertNotNull(archive.getManifest().getMainAttributes().getValue("Gretl-Job-Class"));
            assertTrue(archive.stream().anyMatch(e -> e.getName().contains("closure")));
            assertTrue(archive.stream().allMatch(e -> e.getName().equals("META-INF/MANIFEST.MF") || e.getName().endsWith(".class")));
        }
        var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes);
        var before = Thread.currentThread().getContextClassLoader();
        assertEquals(0, Main.execute(args(jar, root, "2026"), output, output), bytes.toString());
        assertSame(before, Thread.currentThread().getContextClassLoader());
        verifyRow(2026, Files.size(root.resolve("input.txt")));
        assertEquals(0, Main.execute(args(root.resolve("job.groovy"), root, "2027"), output, output,
                (source, environment) -> new ch.so.agi.gretl.runtime.groovy.SourceJobLoader().load(source, environment)), bytes.toString());
        verifyRow(2027, Files.size(root.resolve("input.txt")));
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE IF NOT EXISTS messung(jahr BIGINT, bytes BIGINT); DELETE FROM messung; INSERT INTO messung VALUES (1900, 7);");
        assertEquals(0, Main.execute(args(jar, root, "2028"), output, output), bytes.toString());
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + temporary.resolve("db"), "sa", "");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM messung")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
        }
    }

    private void verifyRow(long year, long size) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + temporary.resolve("db"), "sa", "");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT jahr, bytes FROM messung")) {
            assertTrue(rows.next()); assertEquals(year, rows.getLong(1)); assertEquals(size, rows.getLong(2)); assertFalse(rows.next());
        }
    }

    @Test void compilationDoesNotExecuteAndLoaderRestoresContextOnFailure() throws Exception {
        Path source = temporary.resolve("notexecuted.groovy"), jar = temporary.resolve("job.jar");
        Files.writeString(source, "throw new IllegalStateException('only at execution')");
        new JobCompiler().compile(source, jar, false);
        var before = Thread.currentThread().getContextClassLoader();
        var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes);
        assertEquals(1, Main.execute(args(jar, temporary, "2026"), output, output));
        assertTrue(bytes.toString().contains("only at execution"));
        assertSame(before, Thread.currentThread().getContextClassLoader());
    }

    @Test void invalidParameterAndSqlFailuresAreExplicit() throws Exception {
        Path root = temporary.resolve("job"); Files.createDirectories(root);
        for (String file : new String[]{"job.groovy", "schema.sql", "input.txt"}) Files.copy(example().resolve(file), root.resolve(file));
        Path jar = temporary.resolve("job.jar"); new JobCompiler().compile(root.resolve("job.groovy"), jar, false);
        var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes);
        assertEquals(1, Main.execute(args(jar, root, "no-year"), output, output));
        assertTrue(bytes.toString().contains("jahr"));
        Files.delete(root.resolve("schema.sql")); bytes.reset();
        assertEquals(1, Main.execute(args(jar, root, "2026"), output, output));
        assertTrue(bytes.toString().contains("Task schema"));
        assertTrue(bytes.toString().contains("NoSuchFileException"));
        Files.writeString(root.resolve("schema.sql"), "THIS IS NOT SQL"); bytes.reset();
        assertEquals(1, Main.execute(args(jar, root, "2026"), output, output));
        assertTrue(bytes.toString().contains("Task schema"));
        assertTrue(bytes.toString().contains("JdbcSQLSyntaxErrorException"));
        assertEquals(2, Main.execute(new String[]{"run", jar.toString()}, output, output));
    }

    @Test void malformedArchiveDoesNotLeakTheContextLoader() throws Exception {
        Path jar = temporary.resolve("empty.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("irrelevant.txt"));
            output.write(1); output.closeEntry();
        }
        var before = Thread.currentThread().getContextClassLoader();
        var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes);
        assertEquals(1, Main.execute(args(jar, temporary, "2026"), output, output));
        assertTrue(bytes.toString().contains("Missing Gretl-Job-Class"));
        assertSame(before, Thread.currentThread().getContextClassLoader());
        bytes.reset();
        assertEquals(1, Main.execute(new String[]{"probe", jar.toString(), "missing.Entry"}, output, output));
        assertTrue(bytes.toString().contains("ClassNotFoundException"));
        assertSame(before, Thread.currentThread().getContextClassLoader());
    }
}
