package nl.hackyourfuture.project.backend.config;

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
 * trusted until configured. The issuers are a list of {name, key-set-url} entries, bound from
 * environment variables as {@code APP_INTERNAL_TRUSTEDISSUERS_0_NAME} and
 * {@code APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL}, not a map, because a hyphenated issuer name
 * cannot be set from the environment as a map key (Day 17: Boot binds
 * {@code APP_INTERNAL_TRUSTEDISSUERS_JOBMATCH_JOB_SERVICE} as the key {@code jobmatch.job.service}).
 * identity calls no service, so it signs no service tokens and trusts only this list (Day 42).
 */
@Slf4j
@Component
public class InternalCallers {

    static final String AUDIENCE = "jobmatch-internal";

    private final Map<String, AuthenticationManager> managers = new LinkedHashMap<>();

    public InternalCallers(Environment environment) {
        // The trusted issuers, from configuration; empty by default, and then nothing is trusted.
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
                        audience -> audience != null && audience.contains(AUDIENCE))));
    }

    /** One trusted caller: its issuer name and the URL of its key set. */
    record TrustedIssuer(String name, String keySetUrl) {
    }
}
