package ch.so.agi.gretl.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ApiMetadataTest {

    record Request(Path input) {}
    record Result(long bytes, String note) {}

    static final class Builder implements ConfigBuilder<Request> {
        private Path input;
        private String note = "default";

        void setInput(Path input) {
            this.input = input;
        }

        void setNote(String note) {
            this.note = note;
        }

        @Override
        public Request build() {
            if (input == null) {
                throw new IllegalArgumentException("input fehlt");
            }
            return new Request(input.toAbsolutePath().normalize());
        }
    }

    private static OperationType<Request, Result> operation() {
        InputField<Request, Path> input = Fields.requiredPath(
                "input", "Datei, deren Groesse gelesen wird", Builder.class, Builder::setInput);
        InputField<Request, String> note = Fields.input(
                "note", "text", "Notiz", false, Optional.of("default"),
                List.of("default", "alternate"), "note = 'default'", Builder.class,
                value -> {
                    if (!(value instanceof CharSequence text)) {
                        throw new IllegalArgumentException("note erwartet Text");
                    }
                    String converted = text.toString();
                    if (!List.of("default", "alternate").contains(converted)) {
                        throw new IllegalArgumentException("note hat einen unerlaubten Wert");
                    }
                    return converted;
                },
                Builder::setNote);
        OutputField<Result, Long> bytes = Fields.output(
                "bytes", "integer", "Anzahl Bytes der Datei", false, Result::bytes);
        OutputField<Result, String> noteOut = Fields.output(
                "note", "text", "Notiz", true, Result::note);
        return new OperationType<>(
                "example.file-size", "FileSize", "Dateigroesse lesen",
                Request.class, Result.class, Builder::new,
                () -> new OperationHandler<Request, Result>() {
                    @Override
                    public Result execute(Request request, OperationContext ctx) {
                        return new Result(Files.sizeOf(request.input()), "default");
                    }
                },
                List.of(input, note), List.of(bytes, noteOut),
                "task('messen', FileSize) { input = file('input.xtf') }");
    }

    @Test
    void builderAndHandlerFactoriesReturnFreshInstances() {
        OperationType<Request, Result> op = operation();
        assertNotSame(op.builderFactory().get(), op.builderFactory().get());
        assertNotSame(op.handlerFactory().get(), op.handlerFactory().get());
    }

    @Test
    void metadataListsAreDefensivelyCopied() {
        OperationType<Request, Result> op = operation();
        assertThrows(UnsupportedOperationException.class, () -> op.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> op.outputs().clear());

        List<String> mutable = new ArrayList<>(List.of("allowed"));
        InputField<Request, String> field = Fields.input(
                "name", "text", "beschreibung", false, Optional.empty(), mutable,
                "name = 'allowed'", Builder.class, Object::toString, Builder::setNote);
        mutable.add("changed");
        assertEquals(List.of("allowed"), field.allowedValues());
        assertThrows(UnsupportedOperationException.class, () -> field.allowedValues().clear());
    }

    @Test
    void defaultsAndExplicitValuesUseTheSameConverterAndSetter() throws Exception {
        OperationType<Request, Result> op = operation();
        InputField<Request, String> note = input(op, "note");
        ConfigBuilder<Request> defaultsBuilder = op.builderFactory().get();
        assertTrue(note.defaultValue().isPresent());
        note.assign().accept(defaultsBuilder, note.defaultValue().orElseThrow());

        ConfigBuilder<Request> explicitBuilder = op.builderFactory().get();
        note.assign().accept(explicitBuilder, note.convert().apply(new StringBuilder("alternate")));

        assertEquals("default", noteValue(defaultsBuilder));
        assertEquals("alternate", noteValue(explicitBuilder));
        assertThrows(IllegalArgumentException.class,
                () -> note.convert().apply("not-allowed"));
        assertThrows(IllegalArgumentException.class,
                () -> note.convert().apply(42));
    }

    @Test
    void requiredPathRejectsTextAndWrongBuilder() {
        OperationType<Request, Result> op = operation();
        InputField<Request, Path> input = input(op, "input");
        assertThrows(IllegalArgumentException.class, () -> input.convert().apply("input.xtf"));
        assertThrows(ClassCastException.class,
                () -> input.assign().accept(new ConfigBuilder<Request>() {
                    @Override
                    public Request build() {
                        return null;
                    }
                }, Path.of("input.xtf")));
    }

    @Test
    void duplicateFieldsAndVoidOutputsAreRejected() {
        OperationType<Request, Result> op = operation();
        InputField<Request, ?> first = op.inputs().get(0);
        assertThrows(IllegalArgumentException.class, () -> new OperationType<Request, Result>(
                op.id(), op.symbol(), op.description(), op.requestType(), op.resultType(),
                op.builderFactory(), op.handlerFactory(),
                List.of(first, first), op.outputs(), op.example()));

        OutputField<Result, ?> output = op.outputs().get(0);
        assertThrows(IllegalArgumentException.class, () -> new OperationType<Request, Result>(
                op.id(), op.symbol(), op.description(), op.requestType(), op.resultType(),
                op.builderFactory(), op.handlerFactory(),
                op.inputs(), List.of(output, output), op.example()));

        assertThrows(IllegalArgumentException.class, () -> new OperationType<Request, Void>(
                "void", "Void", "keine Ausgabe", Request.class, Void.class,
                op.builderFactory(), () -> (request, ctx) -> null,
                op.inputs(),
                List.of(new OutputField<Void, Object>("value", "text", "kein Wert", true, value -> null)),
                op.example()));
    }

    @Test
    void blankMetadataAndNullFactoriesAreRejected() {
        OperationType<Request, Result> op = operation();
        assertThrows(IllegalArgumentException.class, () -> new OperationType<Request, Result>(
                " ", op.symbol(), op.description(), op.requestType(), op.resultType(),
                op.builderFactory(), op.handlerFactory(), op.inputs(), op.outputs(), op.example()));
        assertThrows(IllegalArgumentException.class, () -> new OperationType<Request, Result>(
                op.id(), op.symbol(), " ", op.requestType(), op.resultType(),
                op.builderFactory(), op.handlerFactory(), op.inputs(), op.outputs(), op.example()));
        assertThrows(NullPointerException.class, () -> new OperationType<Request, Result>(
                op.id(), op.symbol(), op.description(), op.requestType(), op.resultType(),
                null, op.handlerFactory(), op.inputs(), op.outputs(), op.example()));
        assertThrows(IllegalArgumentException.class, () -> new OutputField<Result, Long>(
                "bytes", "integer", " ", false, Result::bytes));
    }

    @Test
    void outputReaderIsLazyAndReadOnlyByContract() {
        OperationType<Request, Result> op = operation();
        Result result = new Result(17L, "note");
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Result> observed = new AtomicReference<>();
        OutputField<Result, Long> field = new OutputField<>(
                "bytes", "integer", "Bytes", false, value -> {
                    reads.incrementAndGet();
                    observed.set(value);
                    return value.bytes();
                });
        assertEquals(0, reads.get());
        assertEquals(17L, field.read().apply(result));
        assertEquals(1, reads.get());
        assertEquals(result, observed.get());
    }

    @SuppressWarnings("unchecked")
    private static <V> InputField<Request, V> input(OperationType<Request, Result> op, String name) {
        return (InputField<Request, V>) op.inputs().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String noteValue(ConfigBuilder<Request> builder) throws Exception {
        java.lang.reflect.Field note = Builder.class.getDeclaredField("note");
        note.setAccessible(true);
        return (String) note.get(builder);
    }

    private static final class Files {
        static long sizeOf(Path path) {
            try {
                return java.nio.file.Files.size(path);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
