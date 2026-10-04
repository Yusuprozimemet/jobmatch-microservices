package nl.hackyourfuture.project.backend.config;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public half of the monolith's service key (Day 39), for services that verify its tokens;
 * unauthenticated, like {@code /.well-known/jwks.json}. Not routed by the gateway: it is for
 * other services inside the network.
 */
@RestController
@RequiredArgsConstructor
class ServiceJwksController {

    private final ServiceSigningKey key;

    @GetMapping(path = "/.well-known/service-jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> serviceJwks() {
        // publicJwk() already has no private part; toJSONObject() drops private members again, so
        // leaking one here would take two mistakes.
        return new JWKSet(key.publicJwk()).toJSONObject();
    }
}
