package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public half of job-service's own service key (Day 39), for services that verify its tokens;
 * unauthenticated, like {@code /.well-known/jwks.json}. Not routed by the gateway: it is for
 * other services inside the network.
 */
@RestController
class ServiceJwksController {

    private final ServiceSigningKey key;

    public ServiceJwksController(ServiceSigningKey key) {
        this.key = key;
    }

    @GetMapping(path = "/.well-known/service-jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> serviceJwks() {
        // publicJwk() already has no private part; toJSONObject() drops private members again, so
        // leaking one here would take two mistakes.
        return new JWKSet(key.publicJwk()).toJSONObject();
    }
}
