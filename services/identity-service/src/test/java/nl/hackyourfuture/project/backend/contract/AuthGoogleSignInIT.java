package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.StubOidcProvider;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google sign-in, all three branches, against a stubbed OpenID Connect provider.
 *
 * <p>The flow is driven the way a browser drives it, minus the trip to Google: ask for the
 * authorization redirect, read {@code state} and {@code nonce} off it, then call the callback.
 * Everything asserted is a status, a {@code Location}, or what the session can reach
 * afterwards - so the file says nothing about how the identity is stored or where the pending
 * link is parked, both of which Phase 2 changes.
 */
@Import(AuthGoogleSignInIT.StubProviderConfig.class)
@TestPropertySource(properties = {
        // Non-empty, so GoogleOAuth2Config registers and the real verified-email check is the
        // one under test. The registration it builds points at Google and is then overridden
        // by the @Primary bean below, which points at the stub.
        "app.oauth2.google.client-id=" + StubOidcProvider.CLIENT_ID,
        "app.oauth2.google.client-secret=" + StubOidcProvider.CLIENT_SECRET,
        "app.oauth2.google.redirect-uri=http://localhost/api/login/oauth2/code/google"
})
class AuthGoogleSignInIT extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:3000";
    private static final String SIGNED_IN = FRONTEND + "/";
    private static final String NEEDS_TERMS = FRONTEND + "/accept-terms";
    private static final String NEEDS_LINK = FRONTEND + "/login?error=google_link_required";
    private static final String FAILED = FRONTEND + "/login?error=oauth";

    // Step 1: the identity is already linked to an account, so it just signs in.
    @Test
    void signsInAnIdentityThatIsAlreadyLinked() {
        TestUser user = aUser().email("linked@example.test").googleAccount("google-sub-linked").create();
        ApiClient client = anonymous();

        ApiResponse response = signIn(client, "google-sub-linked", user.email());

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(SIGNED_IN);
        assertThat(client.get("/api/users/me").at("/email").asString()).isEqualTo(user.email());
    }

    // A linked account that never agreed goes through the terms screen, logged in already.
    @Test
    void sendsALinkedAccountWithoutTermsToTheTermsScreen() {
        TestUser user = aUser().email("no-terms@example.test")
                .googleAccount("google-sub-no-terms").withoutAcceptedTerms().create();
        ApiClient client = anonymous();

        ApiResponse response = signIn(client, "google-sub-no-terms", user.email());

        assertThat(response.location()).isEqualTo(NEEDS_TERMS);
        assertThat(client.get("/api/users/me").status()).isEqualTo(200);
    }

    // Step 2: nobody owns the address, so the account is created on the spot. Google skips our
    // terms screen, which is why a brand new account lands there rather than at home.
    @Test
    void createsAnAccountWhenTheEmailIsFree() {
        ApiClient client = anonymous();

        ApiResponse response = signIn(client, "google-sub-new", "brand-new@example.test", "Grace Hopper");

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(NEEDS_TERMS);
        ApiResponse me = client.get("/api/users/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.at("/email").asString()).isEqualTo("brand-new@example.test");
        assertThat(me.at("/name").asString()).isEqualTo("Grace Hopper");
    }

    // Step 3: the address already belongs to a password account. An email match is not proof
    // of ownership, so no session is started and the identity waits.
    @Test
    void refusesToLinkOnAnEmailMatchAlone() {
        TestUser existing = aUser().email("taken@example.test").create();
        ApiClient client = anonymous();

        ApiResponse response = signIn(client, "google-sub-taken", existing.email());

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(NEEDS_LINK);
        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    // The password login is the proof, and it claims the parked identity: signing in with
    // Google again afterwards goes straight through.
    @Test
    void theNextPasswordLoginClaimsTheParkedIdentity() {
        TestUser existing = aUser().email("claimed@example.test").create();
        ApiClient client = anonymous();
        signIn(client, "google-sub-claimed", existing.email());

        ApiResponse login = client.post("/api/auth/login",
                Map.of("email", existing.email(), "password", existing.password()));

        assertThat(login.status()).isEqualTo(200);
        ApiResponse second = signIn(anonymous(), "google-sub-claimed", existing.email());
        assertThat(second.location()).isEqualTo(SIGNED_IN);
    }

    // Only a login for the parked address can claim it - otherwise signing in with Google as
    // someone else, then logging in as yourself, would attach their identity to your account.
    @Test
    void aLoginForAnotherAccountDoesNotClaimTheParkedIdentity() {
        TestUser parked = aUser().email("parked@example.test").create();
        TestUser other = aUser().email("other@example.test").create();
        ApiClient client = anonymous();
        signIn(client, "google-sub-parked", parked.email());

        client.post("/api/auth/login", Map.of("email", other.email(), "password", other.password()));

        ApiResponse second = signIn(anonymous(), "google-sub-parked", parked.email());
        assertThat(second.location()).isEqualTo(NEEDS_LINK);
    }

    // The verified-email check runs before the success handler, so an unverified address
    // cannot claim an account or create one.
    @Test
    void refusesAnUnverifiedGoogleAddress() {
        ApiClient client = anonymous();

        ApiResponse response = signInUnverified(client, "google-sub-unverified", "unverified@example.test");

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(FAILED);
        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
        // Nothing was created: the address is still free to register.
        assertThat(anonymous().post("/api/auth/register", Map.of(
                "name", "Nobody", "email", "unverified@example.test",
                "password", "Password123!", "acceptedTerms", true)).status()).isEqualTo(201);
    }

    @Test
    void refusesACallbackWithoutTheStateItIssued() {
        ApiResponse response = anonymous().get("/api/login/oauth2/code/google?code=stub-code&state=forged");

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(FAILED);
    }

    private ApiResponse signIn(ApiClient client, String subject, String email) {
        return signIn(client, subject, email, "Google User");
    }

    private ApiResponse signIn(ApiClient client, String subject, String email, String name) {
        return callback(client, subject, email, name, true);
    }

    private ApiResponse signInUnverified(ApiClient client, String subject, String email) {
        return callback(client, subject, email, "Google User", false);
    }

    /**
     * The browser half of the dance: follow the authorization redirect far enough to read
     * {@code state} and {@code nonce} out of it, tell the stub what to sign, and come back to
     * the callback the way Google would.
     */
    private ApiResponse callback(ApiClient client, String subject, String email, String name, boolean verified) {
        ApiResponse start = client.get("/api/oauth2/authorization/google");
        assertThat(start.status()).isEqualTo(302);

        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUriString(start.location()).build().getQueryParams();
        StubOidcProvider.instance().willIssue(subject, email, verified, name, decode(query.getFirst("nonce")));

        return client.get("/api/login/oauth2/code/google?code=stub-code&state={state}",
                decode(query.getFirst("state")));
    }

    private static String decode(String value) {
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubProviderConfig {

        /**
         * The Google endpoints, replaced by the stub ones. Primary rather than a replacement,
         * so {@code GoogleOAuth2Config} still contributes the OIDC user service that carries
         * the verified-email rule.
         */
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
                    // Resolved against the running server, whatever random port it got.
                    .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                    .scope("openid", "email", "profile")
                    .authorizationUri(provider.authorizationUri())
                    .tokenUri(provider.tokenUri())
                    .jwkSetUri(provider.jwkSetUri())
                    .issuerUri(provider.issuer())
                    // No user-info endpoint: the claims the application needs are in the ID
                    // token, and leaving it out means no second call to fetch them again.
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName("Google")
                    .build());
        }
    }
}
