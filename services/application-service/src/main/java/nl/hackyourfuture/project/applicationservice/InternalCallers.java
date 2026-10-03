package nl.hackyourfuture.project.applicationservice;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Who may call application-service's {@code /internal/**} (Day 39, Day 25, as job-service's Day 17):
 * each caller's token is verified against that caller's key set, with its {@code iss} and
 * {@code aud=jobmatch-internal}. Nothing is trusted that is not configured, application-service's
 * own tokens included.
 *
 * <p>job-service, {@code jobmatch-job-service}, has a property of its own, and application-service
 * does not start without it: it calls {@code /internal/saved-counts}. The others are a list of
 * {@code {name, key-set-url}}, set from the environment as {@code APP_INTERNAL_TRUSTEDISSUERS_0_NAME}
 * and {@code APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL}. Not a map keyed by issuer: Boot binds
 * {@code APP_INTERNAL_TRUSTEDISSUERS_JOBMATCH_JOB_SERVICE} as the key {@code jobmatch.job.service}.
 * And job-service is not a list entry: a list set in a higher-priority source replaces the whole
 * list, so an environment list would wipe it.
 */
@Component
class InternalCallers {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalCallers.class);
    private static final String JOB_SERVICE = "jobmatch-job-service";

    private final Map<String, AuthenticationManager> managers = new TreeMap<>();

    InternalCallers(@Value("${app.internal.job-service-key-set-url:}") String jobServiceKeySetUrl, Environment environment) {
        if (jobServiceKeySetUrl.isBlank()) {
            throw new IllegalStateException("JOB_SERVICE_KEY_SET_URL is not set. Point it at job-service's "
                    + "/.well-known/service-jwks.json: application-service does not start without knowing its main caller.");
        }
        trust(JOB_SERVICE, jobServiceKeySetUrl);
        for (TrustedIssuer issuer : Binder.get(environment)
                .bind("app.internal.trusted-issuers", Bindable.listOf(TrustedIssuer.class))
                .orElse(List.of())) {
            if (issuer.name() == null || issuer.name().isBlank() || issuer.keySetUrl() == null
                    || issuer.keySetUrl().isBlank() || issuer.name().equals(JOB_SERVICE)) {
                throw new IllegalStateException("app.internal.trusted-issuers has " + issuer + ": each entry "
                        + "needs a name and a key-set-url, and " + JOB_SERVICE + " is set by JOB_SERVICE_KEY_SET_URL");
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
