package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.DiagnosticCarrier;
import ch.so.agi.gretl.runtime.api.DiagnosticInfo;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, immutable description of a throwable graph. The original throwable
 * remains available to Java catch code; this type is for outcomes and events.
 *
 * <p>M1 bounds depth, suppressed edges and message length. Full redaction and
 * the detailed logging formatter remain M3 work.</p>
 */
public record FailureInfo(
        String id, String type, String message,
        List<FailureInfo> causes, List<FailureInfo> suppressed, boolean truncated,
        List<Frame> frames, boolean framesTruncated,
        java.util.Optional<SourceLocation> source,
        java.util.Optional<DiagnosticInfo> diagnostic) {

    /** Bounded stack frame retained for structured diagnoses. */
    public record Frame(String className, String methodName, String fileName, int lineNumber) {
        public Frame {
            className = className == null ? "" : className;
            methodName = methodName == null ? "" : methodName;
            fileName = fileName == null ? "" : fileName;
        }
    }

    private static final int MAX_DEPTH = 16;
    private static final int MAX_SUPPRESSED = 8;
    private static final int MAX_MESSAGE_CHARS = 2048;

    public FailureInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        message = message == null ? "" : message;
        causes = List.copyOf(causes);
        suppressed = List.copyOf(suppressed);
        frames = List.copyOf(frames);
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public FailureInfo(
            String id, String type, String message,
            List<FailureInfo> causes, List<FailureInfo> suppressed, boolean truncated,
            List<Frame> frames, boolean framesTruncated,
            java.util.Optional<SourceLocation> source) {
        this(id, type, message, causes, suppressed, truncated, frames,
                framesTruncated, source, java.util.Optional.empty());
    }

    public static FailureInfo from(Throwable throwable) {
        if (throwable == null) {
            throw new IllegalArgumentException("throwable must not be null");
        }
        return capture(throwable, new IdentityHashMap<>(), 0, UUID.randomUUID().toString());
    }

    private static FailureInfo capture(
            Throwable throwable, IdentityHashMap<Throwable, Boolean> seen, int depth, String id) {
        if (seen.put(throwable, Boolean.TRUE) != null) {
            return new FailureInfo(id, throwable.getClass().getName(),
                    "[cyclic throwable reference]", List.of(), List.of(), true,
                    List.of(), false, java.util.Optional.empty(),
                    java.util.Optional.empty());
        }
        boolean depthTruncated = depth >= MAX_DEPTH;
        List<FailureInfo> causes = new ArrayList<>();
        if (!depthTruncated && throwable.getCause() != null) {
            causes.add(capture(throwable.getCause(), seen, depth + 1, id));
        }
        List<FailureInfo> suppressed = new ArrayList<>();
        Throwable[] suppressedRaw = throwable.getSuppressed();
        boolean suppressedTruncated = suppressedRaw.length > MAX_SUPPRESSED;
        if (!depthTruncated) {
            int limit = Math.min(suppressedRaw.length, MAX_SUPPRESSED);
            for (int index = 0; index < limit; index++) {
                suppressed.add(capture(suppressedRaw[index], seen, depth + 1, id));
            }
        }
        boolean causeTruncated = causes.stream().anyMatch(FailureInfo::truncated);
        boolean suppressedChildTruncated = suppressed.stream().anyMatch(FailureInfo::truncated);
        StackTraceElement[] stack = throwable.getStackTrace();
        int frameLimit = Math.min(stack.length, 64);
        List<Frame> frames = new ArrayList<>(frameLimit);
        for (int index = 0; index < frameLimit; index++) {
            StackTraceElement element = stack[index];
            frames.add(new Frame(element.getClassName(), element.getMethodName(),
                    element.getFileName(), element.getLineNumber()));
        }
        java.util.Optional<SourceLocation> source = frames.stream()
                .filter(frame -> frame.fileName() != null
                        && frame.fileName().endsWith(".groovy")
                        && frame.lineNumber() > 0)
                .findFirst()
                .or(() -> frames.stream()
                        .filter(frame -> frame.fileName() != null
                                && !frame.fileName().isBlank() && frame.lineNumber() > 0)
                        .findFirst())
                .map(frame -> new SourceLocation(frame.fileName(), frame.lineNumber(), 0));
        return new FailureInfo(
                id,
                throwable.getClass().getName(),
                shorten(throwable.getMessage()),
                causes,
                suppressed,
                depthTruncated || suppressedTruncated || causeTruncated
                        || suppressedChildTruncated,
                frames,
                stack.length > 64,
                source,
                throwable instanceof DiagnosticCarrier carrier
                        ? java.util.Optional.of(carrier.diagnostic())
                        : java.util.Optional.empty());
    }

    private static String shorten(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS) + "…[truncated]";
    }
}
