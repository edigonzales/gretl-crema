package ch.so.agi.gretl.crema.compiler;

import java.nio.file.Path;

public final class CompilerMain {
    private CompilerMain() {}
    public static void main(String[] args) {
        boolean plain = args.length == 4 && "--plain".equals(args[0]);
        int offset = plain ? 1 : 0;
        if (args.length != 3 + offset || !"--output".equals(args[1 + offset])) {
            System.err.println("Usage: gretl-crema-compile [--plain] source.groovy --output job.jar");
            System.exit(2);
        }
        try {
            new JobCompiler().compile(Path.of(args[offset]), Path.of(args[2 + offset]), plain);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
