package nl.hackyourfuture.project.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * A Google sign-in against {@link StubOidcProvider}, for tests outside {@code contract/}.
 *
 * <p>{@code contract/AuthGoogleSignInIT} drives the same flow with its own private copy, and keeps
 * it: the contract suite does not change for the tests that come after it. A test using this
 * imports {@link Config} and sets the three {@code app.oauth2.google.*} properties that test does.
 */
public final class GoogleSignIn {

    private GoogleSignIn() {
    }

    /**
     * The browser's half: follow the authorization redirect far enough to read {@code state} and
     * {@code nonce}, tell the stub what to sign, and come back to the callback as Google would.
     */
    public static ApiResponse as(ApiClient client, String subject, String email) {
        ApiResponse start = client.get("/api/oauth2/authorization/google");
        if (start.status() != 302) {
            throw new IllegalStateException("The authorization request was not a redirect: " + start.status());
        }
        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUriString(start.location()).build().getQueryParams();
        StubOidcProvider.instance().willIssue(subject, email, true, "Google User", decode(query.getFirst("nonce")));
        return client.get("/api/login/oauth2/code/google?code=stub-code&state={state}",
                decode(query.getFirst("state")));
    }

    private static String decode(String value) {
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    /** Google's endpoints, replaced by the stub's; as in {@code AuthGoogleSignInIT}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        ClientRegistrationRepository stubClientRegistrationRepository() {
            StubOidcProvider provider = StubOidcProvider.instance();
            return new InMemoryClientRegistrationRepository(ClientRegistration
                    .withRegistrationId("google")
                    .clientId(StubOidcProvider.CLIENT_ID)
                    .clientSecret(StubOidcProvider.CLIENT_SECRET)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                    .scope("openid", "email", "profile")
                    .authorizationUri(provider.authorizationUri())
                    .tokenUri(provider.tokenUri())
                    .jwkSetUri(provider.jwkSetUri())
                    .issuerUri(provider.issuer())
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName("Google")
                    .build());
        }
    }
}
