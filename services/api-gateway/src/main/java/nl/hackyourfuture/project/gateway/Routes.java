package nl.hackyourfuture.project.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Where requests go. There is one backend until Phase 3, so one route: the API and the key
 * tokens are verified with. Nothing else is routed, the backend's actuator included; a path
 * outside these is the gateway's own 404.
 */
@Configuration(proxyBeanMethods = false)
class Routes {

    @Bean
    RouterFunction<ServerResponse> backend(@Value("${gateway.backend-url}") String backendUrl) {
        return route("backend")
                .route(path("/api/**").or(path("/.well-known/jwks.json")), http())
                .before(uri(backendUrl))
                .build();
    }
}
