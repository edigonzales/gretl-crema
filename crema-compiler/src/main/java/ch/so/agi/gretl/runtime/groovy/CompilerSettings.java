package ch.so.agi.gretl.runtime.groovy;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Shared Groovy compiler configuration for the script runtime and the later
 * packager. Grape is disabled; no Gradle or native semantics are introduced.
 */
public final class CompilerSettings {
    public static final String TARGET_BYTECODE = "25";
    public static final String GRAPE_TRANSFORMATION =
            "groovy.grape.GrabAnnotationTransformation";

    private CompilerSettings() {
    }

    public static CompilerConfiguration create() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(GretlScript.class.getName());
        configuration.setSourceEncoding(StandardCharsets.UTF_8.name());
        configuration.setTargetBytecode(TARGET_BYTECODE);
        configuration.setDisabledGlobalASTTransformations(Set.of(GRAPE_TRANSFORMATION));
        return configuration;
    }
}
