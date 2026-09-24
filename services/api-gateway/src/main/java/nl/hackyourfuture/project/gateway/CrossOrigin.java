package nl.hackyourfuture.project.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * No other origin may call the API from a browser (Day 15): the browser is same-origin through
 * the Next.js proxy. A preflight is answered 403 here and never forwarded, and no response leaves
 * with an {@code Access-Control-*} header, whoever behind the gateway set it; without
 * {@code Access-Control-Allow-Origin} a browser lets no other origin read the answer.
 *
 * <p>Not Spring's CORS rejection: through the proxy a same-origin request carries the browser's
 * {@code Origin} while its host is the gateway's, and Spring would refuse it as cross-origin.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CrossOrigin extends OncePerRequestFilter {

    private static final String CORS_HEADERS = "access-control-";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                if (!isCors(name)) {
                    super.setHeader(name, value);
                }
            }

            @Override
            public void addHeader(String name, String value) {
                if (!isCors(name)) {
                    super.addHeader(name, value);
                }
            }
        });
    }

    private static boolean isCors(String header) {
        return header.toLowerCase(Locale.ROOT).startsWith(CORS_HEADERS);
    }
}
