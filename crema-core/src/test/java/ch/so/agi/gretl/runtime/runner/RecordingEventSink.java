package ch.so.agi.gretl.runtime.runner;

import java.util.ArrayList;
import java.util.List;

/** Recording event sink for M1 lifecycle assertions. */
final class RecordingEventSink implements RunEventSink {
    private final List<RunEvent> events = new ArrayList<>();

    @Override
    public void emit(RunEvent event) {
        events.add(event);
    }

    List<RunEvent> events() {
        return List.copyOf(events);
    }

    List<String> names() {
        return events.stream().map(RunEvent::eventName).toList();
    }

    long count(String eventName) {
        return events.stream().filter(event -> event.eventName().equals(eventName)).count();
    }

    RunEvent first(String eventName) {
        return events.stream().filter(event -> event.eventName().equals(eventName))
                .findFirst().orElseThrow();
    }

    RunEvent last(String eventName) {
        return events.stream().filter(event -> event.eventName().equals(eventName))
                .reduce((first, second) -> second).orElseThrow();
    }
}
