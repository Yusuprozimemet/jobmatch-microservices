package nl.hackyourfuture.project.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayServerResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Function;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions.rateLimit;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Where requests go. There is one backend until Phase 3: the API and the key tokens are verified
 * with, and the credential routes, rate limited, ahead of the rest. Nothing else is routed, the
 * backend's actuator included; a path outside these is the gateway's own answer: 401 without a
 * token, 404 with one.
 */
@Configuration(proxyBeanMethods = false)
class Routes {

    /** Who the caller is, for the services behind the gateway; the backend itself reads only the token. */
    static final String USER_ID = "X-User-Id";

    @Bean
    RouterFunction<ServerResponse> backend(@Value("${gateway.backend-url}") String backendUrl,
                                           @Value("${gateway.rate-limit.auth-per-minute}") long perMinute,
                                           @Value("${gateway.trusted-proxies}") String trustedProxies) {
        // Where a password is guessed or an account made; not refresh, logout or the password change.
        RouterFunction<ServerResponse> credentials = route("credentials")
                .route(POST("/api/auth/login").or(POST("/api/auth/register"))
                        .or(POST("/api/auth/forgot-password")).or(POST("/api/auth/reset-password")), http())
                .filter(rateLimit(limit -> limit
                        .setCapacity(perMinute)
                        .setPeriod(Duration.ofMinutes(1))
                        .setKeyResolver(RateLimit.client(trustedProxies))
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)))
                .before(uri(backendUrl))
                .before(userId())
                .onError(ResourceAccessException.class, Routes::backendFailed)
                .build();
        return credentials.and(route("backend")
                .route(path("/api/**").or(path("/.well-known/jwks.json")), http())
                .before(uri(backendUrl))
                .before(userId())
                .onError(ResourceAccessException.class, Routes::backendFailed)
                .build());
    }

    /**
     * The backend could not be reached, or did not answer in time: 502 or 504, the gateway's own
     * answers for an upstream that failed, rather than a 500 that says the gateway itself broke.
     * The gateway's response type, not {@code ServerResponse}'s: the rate limit adds a header on
     * the way out, and a plain {@code ServerResponse}'s headers are read-only.
     */
    static ServerResponse backendFailed(Throwable error, ServerRequest request) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return GatewayServerResponse.status(HttpStatus.GATEWAY_TIMEOUT).build();
            }
        }
        return GatewayServerResponse.status(HttpStatus.BAD_GATEWAY).build();
    }

    /**
     * Whatever {@code X-User-Id} the client sent is dropped, and a verified token's subject, the
     * user's id, put in its place. Without the first half anyone could be anyone.
     */
    static Function<ServerRequest, ServerRequest> userId() {
        return request -> {
            ServerRequest.Builder forwarded = ServerRequest.from(request).headers(headers -> headers.remove(USER_ID));
            Authentication caller = SecurityContextHolder.getContext().getAuthentication();
            if (caller instanceof JwtAuthenticationToken token) {
                forwarded.header(USER_ID, token.getToken().getSubject());
            }
            return forwarded.build();
        };
    }
}
