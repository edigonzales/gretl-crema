package bench;

import ch.so.agi.gretl.crema.runner.Main;
import ch.so.agi.gretl.runtime.groovy.SourceJobLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separate benchmark adapter: checkpoint before, never after, the measured job. */
public final class CracLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("bytecode|source h2|hotloop");
        boolean source = args[0].equals("source");
        String job = args[1];
        if (Boolean.getBoolean("crema.crac.prepare")) {
            Files.createDirectories(Path.of("/work"));
            for (int i = 0; i < 3; i++) {
                if (execute(source, job, "/work/training-" + i) != 0) throw new IllegalStateException("Training job failed");
            }
            System.gc();
            System.out.println("CRAC_CHECKPOINT_BEFORE_MEASURED_JOB");
            Class.forName("jdk.crac.Core").getMethod("checkpointRestore").invoke(null);
            System.out.println("CRAC_RESTORE_OK");
        }
        System.exit(execute(source, job, "/work/measured-db"));
    }

    private static int execute(boolean source, String job, String database) {
        String root = "/app/jobs/" + job;
        String entry = job.equals("h2") ? "job" : "hotloop";
        String[] command = {"run", root + "/" + entry + (source ? ".groovy" : ".jar"),
                "--job-root", root, "--db", database, "--param",
                job.equals("h2") ? "jahr=2026" : "iterations=100000"};
        return source ? Main.execute(command, System.out, System.err,
                (path, environment) -> new SourceJobLoader().load(path, environment))
                : Main.execute(command, System.out, System.err);
    }
}
