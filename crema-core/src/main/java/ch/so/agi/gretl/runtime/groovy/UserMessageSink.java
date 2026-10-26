package ch.so.agi.gretl.runtime.groovy;

/** Sink for explicit application-level {@code println} messages. */
@FunctionalInterface
public interface UserMessageSink {
    void println(String message);

    UserMessageSink NO_OP = message -> {
    };
}
