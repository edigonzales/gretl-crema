package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParameterResolverTest {

    @TempDir
    Path tempDir;

    private JobDefinition definition(ParameterDefinition... parameters) {
        return RunnerTestSupport.definition(tempDir, builder -> {
            for (ParameterDefinition parameter : parameters) {
                builder.registerParameter(parameter);
            }
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.defaults("a");
        });
    }

    @Test
    void convertsAllFourTypesAndAppliesDefaults() {
        JobDefinition definition = definition(
                new ParameterDefinition("name", ParameterValueType.TEXT,
                        "Name", false, Optional.of("default")),
                new ParameterDefinition("flag", ParameterValueType.BOOLEAN,
                        "Flag", false, Optional.of(false)),
                new ParameterDefinition("count", ParameterValueType.INTEGER,
                        "Count", false, Optional.of(7)),
                new ParameterDefinition("dir", ParameterValueType.PATH,
                        "Dir", false, Optional.of("relative/dir")));

        ResolvedParameters resolved = new ParameterResolver(tempDir)
                .resolve(definition, ParameterSources.empty(), ParameterMode.RUN);

        assertEquals("default", resolved.get("name"));
        assertEquals(false, resolved.get("flag"));
        assertEquals(7L, resolved.get("count"));
        assertEquals(tempDir.resolve("relative/dir").normalize(), resolved.get("dir"));
        assertEquals(ResolvedParameters.ParameterSource.DEFAULT, resolved.source("name"));
    }

    @Test
    void precedenceIsCliThenFileThenRuntimeConfigThenDefault() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.TEXT, "P", false, Optional.of("default")));
        ParameterResolver resolver = new ParameterResolver(tempDir);

        assertEquals("cli", resolver.resolve(definition, new ParameterSources(
                Map.of("p", "config"), Map.of("p", "file"), Map.of("p", "cli")),
                ParameterMode.RUN).get("p"));
        assertEquals("file", resolver.resolve(definition, new ParameterSources(
                Map.of("p", "config"), Map.of("p", "file"), Map.of()),
                ParameterMode.RUN).get("p"));
        assertEquals("config", resolver.resolve(definition, new ParameterSources(
                Map.of("p", "config"), Map.of(), Map.of()),
                ParameterMode.RUN).get("p"));
        assertEquals("default", resolver.resolve(definition, ParameterSources.empty(),
                ParameterMode.RUN).get("p"));
    }

    @Test
    void runtimeConfigUnknownEntryIsIgnoredButExplicitUnknownNamesFail() {
        JobDefinition definition = definition(new ParameterDefinition(
                "quelle", ParameterValueType.TEXT, "Quelle", false, Optional.empty()));
        ParameterResolver resolver = new ParameterResolver(tempDir);

        ResolvedParameters ignored = resolver.resolve(definition, new ParameterSources(
                Map.of("commonButUnknown", "ignored"), Map.of(), Map.of()), ParameterMode.RUN);
        assertNull(ignored.get("quelle"));

        ParameterException fileError = assertThrows(ParameterException.class,
                () -> resolver.resolve(definition, new ParameterSources(
                        Map.of(), Map.of("unknown", "x"), Map.of()), ParameterMode.RUN));
        assertEquals("UNKNOWN_PARAMETER", fileError.code());

        ParameterException cliError = assertThrows(ParameterException.class,
                () -> resolver.resolve(definition, new ParameterSources(
                        Map.of(), Map.of(), Map.of("qulle", "x")), ParameterMode.RUN));
        assertTrue(cliError.getMessage().contains("quelle"));
    }

    @Test
    void requiredValueIsRequiredOnlyInRunMode() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.TEXT, "Pflicht", true, Optional.empty()));
        ParameterResolver resolver = new ParameterResolver(tempDir);

        ParameterException run = assertThrows(ParameterException.class,
                () -> resolver.resolve(definition, ParameterSources.empty(), ParameterMode.RUN));
        assertEquals("MISSING_REQUIRED_PARAMETER", run.code());

        ResolvedParameters check = resolver.resolve(
                definition, ParameterSources.empty(), ParameterMode.CHECK);
        assertEquals(List.of("p"), check.missingRequired());
        assertNull(check.get("p"));
        assertEquals(ParameterMode.PLAN, ParameterMode.PLAN);
    }

    @Test
    void optionalEmptyTextIsPreservedAndDoesNotUseDefault() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.TEXT, "P", false, Optional.of("default")));
        ResolvedParameters resolved = new ParameterResolver(tempDir).resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("p", "")), ParameterMode.RUN);
        assertEquals("", resolved.get("p"));
        assertEquals(ResolvedParameters.ParameterSource.COMMAND_LINE, resolved.source("p"));
    }

    @Test
    void requiredEmptyTextIsRejected() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.TEXT, "P", true, Optional.empty()));
        ParameterException exception = assertThrows(ParameterException.class,
                () -> new ParameterResolver(tempDir).resolve(definition,
                        new ParameterSources(Map.of(), Map.of(), Map.of("p", "")),
                        ParameterMode.RUN));
        assertEquals("INVALID_PARAMETER_VALUE", exception.code());
    }

    @Test
    void booleanIntegerAndPathTransportValuesAreStrict() {
        JobDefinition definition = definition(
                new ParameterDefinition("b", ParameterValueType.BOOLEAN, "B", false, Optional.empty()),
                new ParameterDefinition("i", ParameterValueType.INTEGER, "I", false, Optional.empty()),
                new ParameterDefinition("p", ParameterValueType.PATH, "P", false, Optional.empty()));
        ParameterResolver resolver = new ParameterResolver(tempDir);

        assertThrows(ParameterException.class, () -> resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("b", "yes")), ParameterMode.RUN));
        assertThrows(ParameterException.class, () -> resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("i", "1.5")), ParameterMode.RUN));
        assertThrows(ParameterException.class, () -> resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("i", "9223372036854775808")),
                ParameterMode.RUN));
        assertThrows(ParameterException.class, () -> resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("p", " ")), ParameterMode.RUN));

        assertEquals(true, resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("b", "true")),
                ParameterMode.RUN).get("b"));
        assertEquals(-12L, resolver.resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("i", "-12")),
                ParameterMode.RUN).get("i"));
    }

    @Test
    void pathIsNormalizedAgainstTheOriginalStartDirectory() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.PATH, "P", false, Optional.empty()));
        ResolvedParameters resolved = new ParameterResolver(tempDir).resolve(definition,
                new ParameterSources(Map.of(), Map.of(),
                        Map.of("p", "sub/../file.txt")), ParameterMode.RUN);
        assertEquals(tempDir.resolve("file.txt").normalize(), resolved.get("p"));

        Path absolute = tempDir.resolve("absolute.txt").toAbsolutePath();
        ResolvedParameters absoluteResolved = new ParameterResolver(tempDir).resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("p", absolute.toString())),
                ParameterMode.RUN);
        assertEquals(absolute, absoluteResolved.get("p"));
    }

    @Test
    void textIsNotTrimmed() {
        JobDefinition definition = definition(new ParameterDefinition(
                "p", ParameterValueType.TEXT, "P", false, Optional.empty()));
        ResolvedParameters resolved = new ParameterResolver(tempDir).resolve(definition,
                new ParameterSources(Map.of(), Map.of(), Map.of("p", "  x  ")),
                ParameterMode.RUN);
        assertEquals("  x  ", resolved.get("p"));
    }

    @Test
    void declarationRulesRejectRequiredWithDefaultAndUnknownType() {
        ParameterException requiredDefault = assertThrows(ParameterException.class,
                () -> new ParameterDefinition("p", ParameterValueType.TEXT, "P", true,
                        Optional.of("x")));
        assertEquals("INVALID_PARAMETER_DECLARATION", requiredDefault.code());

        ParameterException unknownType = assertThrows(ParameterException.class,
                () -> ParameterValueType.parse("date"));
        assertEquals("UNKNOWN_PARAMETER_TYPE", unknownType.code());
    }
}
