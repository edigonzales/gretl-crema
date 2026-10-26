package bench;

import ch.so.agi.gretl.crema.runner.Main;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Prepare shared runtime code only; restore invokes Main with fresh CLI arguments. */
public final class GenericCracLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Preparation takes no job arguments");
        // Preparation masks the image's demo directory with an empty tmpfs.
        try (var paths = Files.walk(Path.of("/app/jobs"))) {
            if (paths.anyMatch(Files::isRegularFile)) throw new IllegalStateException("Measured jobs visible during preparation");
        }
        Path work = Path.of("/work/generic-training");
        Files.createDirectories(work);
        for (int i = 0; i < 3; i++) {
            String[] command = {"run", "/app/training/runtimeWarmup.jar", "--job-root", "/app/training",
                    "--db", work.resolve("training-" + i).toString(), "--param", "seed=" + (i + 1)};
            if (Main.execute(command, System.out, System.err) != 0) throw new IllegalStateException("Runtime training failed");
        }
        try (var paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        System.gc();
        System.out.println("CRAC_GENERIC_CHECKPOINT jobs-visible=0 training-runs=3");
        Class.forName("jdk.crac.Core").getMethod("checkpointRestore").invoke(null);
        // A valid restore invokes Main.main(), which exits with the actual job status.
        throw new IllegalStateException("Restore requires GRETL Main and fresh run arguments");
    }
}
