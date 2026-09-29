package nl.hackyourfuture.project.matchingservice;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The W3C {@code traceparent} is read whether or not spans are exported (Day 15), as job-service's
 * {@code TracingConfig} explains: without it, with export off, the service would log under a trace
 * of its own and pass that one on to identity, job-service and the model.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    public TextMapPropagator w3cTraceContext() {
        return W3CTraceContextPropagator.getInstance();
    }
}
