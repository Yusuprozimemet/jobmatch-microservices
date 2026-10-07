package nl.hackyourfuture.project.jobservice;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Who may call job-service's {@code /internal/**} (Day 39, Day 17): each caller's token is
 * verified against that caller's key set, with its {@code iss} and {@code aud=jobmatch-internal}.
 * Nothing is trusted that is not configured, job-service's own tokens included.
 *
 * <p>Callers are a list of {@code {name, key-set-url}}, set from the environment as
 * {@code APP_INTERNAL_TRUSTEDISSUERS_0_NAME} and {@code APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL}.
 * Not a map keyed by issuer: Boot binds {@code APP_INTERNAL_TRUSTEDISSUERS_JOBMATCH_JOB_SERVICE}
 * as the key {@code jobmatch.job.service}.
 */
@Component
class InternalCallers {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalCallers.class);

    private final Map<String, AuthenticationManager> managers = new TreeMap<>();

    InternalCallers(Environment environment) {
        for (TrustedIssuer issuer : Binder.get(environment)
                .bind("app.internal.trusted-issuers", Bindable.listOf(TrustedIssuer.class))
                .orElse(List.of())) {
            if (issuer.name() == null || issuer.name().isBlank() || issuer.keySetUrl() == null
                    || issuer.keySetUrl().isBlank()) {
                throw new IllegalStateException("app.internal.trusted-issuers has " + issuer + ": each entry needs a name and a key-set-url");
            }
            trust(issuer.name(), issuer.keySetUrl());
        }
        LOGGER.info("Internal routes accept service tokens from {}", managers.keySet());
    }

    /** The issuers trusted, by name. */
    Set<String> issuers() {
        return managers.keySet();
    }

    /** For the {@code /internal/**} chain: an issuer not trusted resolves to nothing, a 401. */
    AuthenticationManagerResolver<HttpServletRequest> resolver() {
        return new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    private void trust(String issuer, String keySetUrl) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keySetUrl).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(ServiceTokens.AUDIENCE))));
        managers.put(issuer, new JwtAuthenticationProvider(decoder)::authenticate);
    }

    /** One trusted caller: its issuer name and the URL of its key set. */
    record TrustedIssuer(String name, String keySetUrl) {
    }
}
