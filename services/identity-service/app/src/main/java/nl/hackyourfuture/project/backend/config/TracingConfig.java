package nl.hackyourfuture.project.backend.config;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The W3C {@code traceparent} is read whether or not spans are exported (Day 15). Spring Boot 4
 * sets up propagation only while export is on and installs a no-op otherwise, so with
 * {@code TRACING_EXPORT_ENABLED} off, as it is by default, the backend would ignore the trace the
 * API gateway forwards and log under one of its own: the trace id, which is the correlation id,
 * would then match nothing the gateway logged. The gateway declares the same bean.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    public TextMapPropagator w3cTraceContext() {
        return W3CTraceContextPropagator.getInstance();
    }
}
