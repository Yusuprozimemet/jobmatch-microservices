package nl.hackyourfuture.project.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One line per request, carrying its trace id (Day 15). The trace id is the correlation id: the
 * gateway starts or continues the W3C {@code traceparent}, forwards it, and the backend logs the
 * same id, so one id finds a request on both sides. Inside the tracing filter, which Spring Boot
 * orders first, so the id is in the log context when the line is written.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class AccessLog extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLog.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("{} {} {}", request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }
}
