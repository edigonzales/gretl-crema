package ch.so.agi.gretl.crema.runner;

import ch.so.agi.gretl.crema.MinimalProvider;
import ch.so.agi.gretl.runtime.groovy.DefinitionEnvironment;
import ch.so.agi.gretl.runtime.groovy.GroovyJobLoader;
import ch.so.agi.gretl.runtime.runner.*;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {}

    @FunctionalInterface public interface SourceLoader {
        JobDefinition load(Path source, DefinitionEnvironment environment);
    }

    public static void main(String[] args) { System.exit(execute(args, System.out, System.err)); }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        return execute(args, out, err, null);
    }

    /** Source loader is supplied only by the JVM compiler tool, never the native entry point. */
    public static int execute(String[] args, PrintStream out, PrintStream err, SourceLoader sourceLoader) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("--version"))) {
                out.println(args[0].equals("--version") ? "gretl-crema 0.1.0 (Crema experiment)"
                        : "Usage: gretl-crema run job.jar --job-root DIR --db FILE [--param key=value]\n"
                          + "       gretl-crema probe jar class");
                return 0;
            }
            if (args.length == 3 && args[0].equals("probe")) {
                try (var archive = new JobArchive(Path.of(args[1]).toAbsolutePath())) {
                    Object instance = archive.loader().loadClass(args[2]).getDeclaredConstructor().newInstance();
                    if (!(instance instanceof Runnable runnable)) throw new IllegalArgumentException("Probe must implement Runnable");
                    runnable.run();
                    return 0;
                }
            }
            var options = parse(args);
            var registry = OperationRegistry.fromProviders(List.of(new MinimalProvider()));
            var environment = new DefinitionEnvironment(registry, options.root(), Path.of("").toAbsolutePath(), out::println);
            // Explicit driver registration avoids JDBC discovery depending on the job loader.
            org.h2.Driver.load();
            if (sourceLoader != null) {
                return run(sourceLoader.load(options.input(), environment), options, out, err);
            }
            try (var archive = new JobArchive(options.input())) {
                var definition = new GroovyJobLoader().loadCompiled(archive.entryClass(),
                        options.root().resolve(archive.sourceName()), environment, archive.loader());
                return run(definition, options, out, err);
            }
        } catch (UsageException failure) {
            err.println(failure.getMessage());
            return 2;
        } catch (Throwable failure) {
            failure.printStackTrace(err);
            return 1;
        }
    }

    private static int run(JobDefinition definition, Options options, PrintStream out, PrintStream err) throws Exception {
        var parameters = new ParameterResolver(Path.of("").toAbsolutePath()).resolve(definition,
                new ParameterSources(Map.of(), Map.of(), options.parameters()), ParameterMode.RUN);
        var plan = new ExecutionPlanner().plan(definition, List.of());
        Files.createDirectories(options.database().getParent());
        var input = RunInput.builder().jobRoot(options.root()).jobName(options.input().getFileName().toString())
                .parameters(parameters)
                .taskConfig(Map.of("demo", Map.of("jdbcUrl", "jdbc:h2:file:" + options.database(), "user", "sa")))
                .eventSink(event -> {
                    if (event.eventName().equals("gretl.task.started") || event.eventName().equals("gretl.task.finished")) {
                        out.println(event.eventName() + " task=" + event.taskId().orElse("-") + " " + event.outcome());
                    }
                }).build();
        JobOutcome outcome = new SequentialJobRunner().run(definition, plan, input);
        for (var task : outcome.tasks()) {
            task.failure().ifPresent(failure -> { err.println("Task " + task.taskId() + ":"); printFailure(failure, err, "  "); });
        }
        outcome.primaryFailure().ifPresent(failure -> printFailure(failure, err, ""));
        out.println("job.status=" + outcome.status());
        return outcome.exitCode() == 0 ? 0 : 1;
    }

    private static void printFailure(FailureInfo failure, PrintStream err, String indent) {
        err.println(indent + failure.type() + ": " + failure.message());
        failure.causes().forEach(cause -> printFailure(cause, err, indent + "  caused by: "));
        failure.suppressed().forEach(cause -> printFailure(cause, err, indent + "  suppressed: "));
    }

    private static Options parse(String[] args) {
        if (args.length < 2 || !args[0].equals("run")) throw new UsageException("Expected run <job.jar> or probe <jar> <class>; use --help");
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path root = null, database = null;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 == args.length) throw new UsageException("Missing value for " + args[i]);
            switch (args[i]) {
                case "--job-root" -> { if (root != null) throw new UsageException("Duplicate --job-root"); root = Path.of(args[i + 1]).toAbsolutePath().normalize(); }
                case "--db" -> { if (database != null) throw new UsageException("Duplicate --db"); database = Path.of(args[i + 1]).toAbsolutePath().normalize(); }
                case "--param" -> {
                    String value = args[i + 1]; int equals = value.indexOf('=');
                    if (equals < 1 || parameters.putIfAbsent(value.substring(0, equals), value.substring(equals + 1)) != null)
                        throw new UsageException("Expected unique --param key=value");
                }
                default -> throw new UsageException("Unknown option: " + args[i]);
            }
        }
        if (root == null || database == null) throw new UsageException("--job-root and --db are required");
        if (database.toString().contains(";")) throw new UsageException("--db must be a file path without H2 URL options");
        return new Options(input, root, database, Map.copyOf(parameters));
    }

    private record Options(Path input, Path root, Path database, Map<String, String> parameters) {}
    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) { super(message); }
    }
}
