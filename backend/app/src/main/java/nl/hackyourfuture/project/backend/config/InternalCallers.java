package nl.hackyourfuture.project.backend.config;

import com.nimbusds.jose.JOSEException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trusts service tokens from authorized issuers for /internal/** (Day 39). Each issuer's token is
 * verified against that issuer's public key, with iss and aud=jobmatch-internal; nothing else is
 * trusted until configured (Day 17 adds job-service). The monolith trusts its own tokens in process,
 * with the service key's public half.
 */
@Slf4j
@Component
public class InternalCallers {

    private final AuthenticationManagerResolver<String> issuerResolver;

    public InternalCallers(ServiceSigningKey key, Environment environment) {
        Map<String, AuthenticationManager> managers = new HashMap<>();

        // The monolith is its own first caller (Days 18-19), and a key-set URL cannot know a test's
        // random port, so it trusts itself in process.
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.publicJwk().toRSAPublicKey()).build();
            setJwtValidator(decoder, ServiceTokens.ISSUER);
            managers.put(ServiceTokens.ISSUER, new JwtAuthenticationProvider(decoder)::authenticate);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not set up in-process JWT decoding", e);
        }

        // Trusted external issuers from configuration, empty by default (Day 17 adds job-service).
        Map<String, String> trustedIssuers = Binder.get(environment)
                .bind("app.internal.trusted-issuers", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());

        for (Map.Entry<String, String> issuer : trustedIssuers.entrySet()) {
            String issuerName = issuer.getKey();
            String keySetUrl = issuer.getValue();
            NimbusJwtDecoder externalDecoder = NimbusJwtDecoder.withJwkSetUri(keySetUrl).build();
            setJwtValidator(externalDecoder, issuerName);
            managers.put(issuerName, new JwtAuthenticationProvider(externalDecoder)::authenticate);
        }

        this.issuerResolver = managers::get;

        log.info("Internal routes accept service tokens from [{}]", String.join(", ", managers.keySet()));
    }

    /**
     * A resolver for /internal/** security filters: issuer → authentication manager from the
     * trusted list. An issuer not in the map yields null, which Spring turns into a 401.
     */
    public AuthenticationManagerResolver<HttpServletRequest> resolver() {
        return new JwtIssuerAuthenticationManagerResolver(issuerResolver);
    }

    private static void setJwtValidator(NimbusJwtDecoder decoder, String issuer) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(ServiceTokens.AUDIENCE))));
    }
}
