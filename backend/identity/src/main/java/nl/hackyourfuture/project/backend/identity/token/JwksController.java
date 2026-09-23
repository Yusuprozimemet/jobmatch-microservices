package nl.hackyourfuture.project.backend.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public key access tokens are verified with, as a JSON Web Key Set (Day 12).
 *
 * <p>Public and unauthenticated: whoever verifies a token has no token of its own to show. Outside
 * {@code /api}, so the browser's proxy does not forward it; it is for the gateway (Day 15).
 */
@Tag(name = "Tokens")
@RestController
@RequiredArgsConstructor
class JwksController {

    private final SigningKey key;

    @Operation(summary = "The public key access tokens are signed with")
    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> jwks() {
        // publicJwk() already has no private part; toJSONObject() drops private members again, so
        // leaking one here would take two mistakes.
        return new JWKSet(key.publicJwk()).toJSONObject();
    }
}
