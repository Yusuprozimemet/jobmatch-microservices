package nl.hackyourfuture.project.matchingservice;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every span that ends, in memory, for the test to read. */
final class EndedSpans implements SpanProcessor {

    private final List<SpanData> ended = new CopyOnWriteArrayList<>();

    public List<SpanData> all() {
        return List.copyOf(ended);
    }

    public void clear() {
        ended.clear();
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        ended.add(span.toSpanData());
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    /** Spring Boot configuration: every SpanProcessor bean is added to the tracer provider it builds. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        EndedSpans endedSpans() {
            return new EndedSpans();
        }
    }
}
