package example;

import ch.so.agi.gretl.runtime.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * M0 example extension. This is a deliberately small, real operation used to
 * prove the same SPI, metadata and result surface for an externally built JAR.
 */
public final class ExampleProvider implements OperationProvider {
    public record Request(Path input) {}
    public record Result(long bytes) {}

    public static final class Builder implements ConfigBuilder<Request> {
        private Path input;
        public void setInput(Path value) { input = value; }
        @Override public Request build() {
            if (input == null) throw new IllegalArgumentException("input fehlt");
            return new Request(input.toAbsolutePath().normalize());
        }
    }

    @Override public Collection<OperationType<?, ?>> operations() {
        InputField<Request, Path> input = Fields.requiredPath(
            "input", "Datei, deren Grösse gelesen wird", Builder.class, Builder::setInput);
        OutputField<Result, Long> bytes = Fields.output(
            "bytes", "integer", "Anzahl Bytes der Datei", false, Result::bytes);
        return List.of(new OperationType<Request, Result>(
            "example.file-size", "FileSize", "Dateigrösse lesen",
            Request.class, Result.class, Builder::new,
            () -> new OperationHandler<Request, Result>() {
                    @Override
                    public Result execute(Request request, OperationContext ctx)
                            throws Exception {
                        ctx.cancellation().throwIfCancellationRequested();
                        return new Result(Files.size(request.input()));
                    }
                },
            List.of(input), List.of(bytes),
            "task('messen', FileSize) { input = file('input.xtf') }\n"
                + "defaults 'messen'\n"));
    }
}
