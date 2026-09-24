package nl.hackyourfuture.project.gateway;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The W3C {@code traceparent}, read and forwarded whether or not spans are exported (Day 15).
 * Spring Boot 4.0 sets up propagation only while export is on, and installs a no-op otherwise:
 * with {@code TRACING_EXPORT_ENABLED} off, as it is by default, the gateway would neither continue
 * a caller's trace nor pass its own on, and the trace id could not correlate anything.
 */
@Configuration(proxyBeanMethods = false)
class Tracing {

    @Bean
    TextMapPropagator w3cTraceContext() {
        return W3CTraceContextPropagator.getInstance();
    }
}
