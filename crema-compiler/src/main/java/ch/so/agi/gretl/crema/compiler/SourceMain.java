package ch.so.agi.gretl.crema.compiler;

import ch.so.agi.gretl.crema.runner.Main;
import ch.so.agi.gretl.runtime.groovy.SourceJobLoader;

/** Reference-only JVM source start, sharing parameter resolution and execution. */
public final class SourceMain {
    private SourceMain() {}
    public static void main(String[] args) {
        System.exit(Main.execute(args, System.out, System.err,
                (path, environment) -> new SourceJobLoader().load(path, environment)));
    }
}
