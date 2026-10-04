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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trusts service tokens from authorized issuers for /internal/** (Day 39). Each issuer's token is
 * verified against that issuer's public key, with iss and aud=jobmatch-internal; nothing else is
 * trusted until configured. The monolith trusts its own tokens in process, with the service key's
 * public half. Other issuers are a list of {name, key-set-url} entries, bound from environment
 * variables as {@code APP_INTERNAL_TRUSTEDISSUERS_0_NAME} and {@code APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL},
 * not a map, because a hyphenated issuer name cannot be set from the environment as a map key
 * (Day 17: Boot binds {@code APP_INTERNAL_TRUSTEDISSUERS_JOBMATCH_JOB_SERVICE} as the key
 * {@code jobmatch.job.service}).
 */
@Slf4j
@Component
public class InternalCallers {

    private final Map<String, AuthenticationManager> managers = new LinkedHashMap<>();

    public InternalCallers(ServiceSigningKey key, Environment environment) {
        // The monolith is its own first caller (Days 18-19), and a key-set URL cannot know a test's
        // random port, so it trusts itself in process.
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.publicJwk().toRSAPublicKey()).build();
            setJwtValidator(decoder, ServiceTokens.ISSUER);
            managers.put(ServiceTokens.ISSUER, new JwtAuthenticationProvider(decoder)::authenticate);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not set up in-process JWT decoding", e);
        }

        // Trusted external issuers from configuration, empty by default. Track D adds job-service here.
        for (TrustedIssuer issuer : Binder.get(environment)
                .bind("app.internal.trusted-issuers", Bindable.listOf(TrustedIssuer.class))
                .orElse(List.of())) {
            if (issuer.name() == null || issuer.name().isBlank() || issuer.keySetUrl() == null
                    || issuer.keySetUrl().isBlank()) {
                throw new IllegalStateException("app.internal.trusted-issuers has " + issuer + ": each entry "
                        + "needs a name and a key-set-url");
            }
            NimbusJwtDecoder externalDecoder = NimbusJwtDecoder.withJwkSetUri(issuer.keySetUrl()).build();
            setJwtValidator(externalDecoder, issuer.name());
            managers.put(issuer.name(), new JwtAuthenticationProvider(externalDecoder)::authenticate);
        }

        log.info("Internal routes accept service tokens from [{}]", String.join(", ", managers.keySet()));
    }

    /** The issuers trusted, by name. */
    Set<String> issuers() {
        return managers.keySet();
    }

    /**
     * A resolver for /internal/** security filters: issuer → authentication manager from the
     * trusted list. An issuer not in the map yields null, which Spring turns into a 401.
     */
    public AuthenticationManagerResolver<HttpServletRequest> resolver() {
        return new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    private static void setJwtValidator(NimbusJwtDecoder decoder, String issuer) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(ServiceTokens.AUDIENCE))));
    }

    /** One trusted caller: its issuer name and the URL of its key set. */
    record TrustedIssuer(String name, String keySetUrl) {
    }
}
