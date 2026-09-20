package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forgotten-password lifecycle: request a link, redeem it once, and never again.
 *
 * <p>The token is read out of {@code password_reset_tokens} because the only other place it
 * exists is the email, and the suite does not run a mail server. That table travels with these
 * endpoints into identity-service, so the read survives the split - but it is the one thing
 * here that is not the HTTP contract, and it is deliberately confined to {@link #tokenFor}.
 */
class AuthPasswordResetIT extends IntegrationTest {

    private static final String EXPIRED_OR_INVALID = "Invalid or expired password reset token";

    @Test
    void issuesALinkForARegisteredAddress() {
        TestUser user = aUser().create();

        ApiResponse response = forgotPassword(user.email());

        assertThat(response.status()).isEqualTo(200);
        assertThat(tokenFor(user.email())).isPresent();
    }

    // Always 200, whatever the address. Anything else would let a caller test which addresses
    // are registered.
    @Test
    void answersTheSameForAnAddressThatDoesNotExist() {
        ApiResponse response = forgotPassword("nobody@example.test");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isNullOrEmpty();
    }

    // A Google-only account has no password to reset, so the link would dead-end. It is
    // skipped silently rather than refused, for the same reason.
    @Test
    void answersTheSameForAGoogleOnlyAccountAndIssuesNoLink() {
        TestUser google = aUser().email("google-only@example.test").googleAccount("google-sub-2").create();

        ApiResponse response = forgotPassword(google.email());

        assertThat(response.status()).isEqualTo(200);
        assertThat(tokenFor(google.email())).isEmpty();
    }

    @Test
    void rejectsAnAddressThatIsNotAnEmail() {
        ApiResponse response = forgotPassword("not-an-email");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/email").isMissingNode()).isFalse();
    }

    @Test
    void aValidTokenSetsTheNewPassword() {
        TestUser user = aUser().create();
        forgotPassword(user.email());

        ApiResponse response = resetPassword(tokenFor(user.email()).orElseThrow(), "BrandNewPassword1!");

        assertThat(response.status()).isEqualTo(200);
        assertThat(login(user.email(), "BrandNewPassword1!").status()).isEqualTo(200);
    }

    @Test
    void theOldPasswordStopsWorking() {
        TestUser user = aUser().create();
        forgotPassword(user.email());

        resetPassword(tokenFor(user.email()).orElseThrow(), "BrandNewPassword1!");

        assertThat(login(user.email(), user.password()).status()).isEqualTo(401);
    }

    // Single use: redeeming the token deletes it, so a captured link is worthless afterwards.
    @Test
    void rejectsATokenThatWasAlreadyUsed() {
        TestUser user = aUser().create();
        forgotPassword(user.email());
        String token = tokenFor(user.email()).orElseThrow();
        resetPassword(token, "BrandNewPassword1!");

        ApiResponse response = resetPassword(token, "AnotherPassword1!");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).isEqualTo(EXPIRED_OR_INVALID);
        assertThat(login(user.email(), "AnotherPassword1!").status()).isEqualTo(401);
    }

    @Test
    void rejectsATokenThatHasExpired() {
        TestUser user = aUser().create();
        forgotPassword(user.email());
        String token = tokenFor(user.email()).orElseThrow();
        expire(token);

        ApiResponse response = resetPassword(token, "BrandNewPassword1!");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).isEqualTo(EXPIRED_OR_INVALID);
    }

    @Test
    void rejectsATokenThatWasNeverIssued() {
        ApiResponse response = resetPassword("11111111-2222-3333-4444-555555555555", "BrandNewPassword1!");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).isEqualTo(EXPIRED_OR_INVALID);
    }

    // Asking for a second link invalidates the first, so only the newest email works.
    @Test
    void asksAgainAndOnlyTheNewestLinkWorks() {
        TestUser user = aUser().create();
        forgotPassword(user.email());
        String first = tokenFor(user.email()).orElseThrow();
        forgotPassword(user.email());
        String second = tokenFor(user.email()).orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(resetPassword(first, "BrandNewPassword1!").status()).isEqualTo(400);
        assertThat(resetPassword(second, "BrandNewPassword1!").status()).isEqualTo(200);
    }

    @Test
    void rejectsANewPasswordShorterThanSixCharacters() {
        TestUser user = aUser().create();
        forgotPassword(user.email());

        ApiResponse response = resetPassword(tokenFor(user.email()).orElseThrow(), "12345");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/newPassword").asString()).isEqualTo("Password must be at least 6 characters");
    }

    private ApiResponse forgotPassword(String email) {
        return anonymous().post("/api/auth/forgot-password", Map.of("email", email));
    }

    private ApiResponse resetPassword(String token, String newPassword) {
        return anonymous().post("/api/auth/reset-password", Map.of("token", token, "newPassword", newPassword));
    }

    private ApiResponse login(String email, String password) {
        return anonymous().post("/api/auth/login", Map.of("email", email, "password", password));
    }

    private Optional<String> tokenFor(String email) {
        return jdbc().sql("""
                        SELECT t.token
                        FROM password_reset_tokens t
                        JOIN users u ON u.id = t.user_id
                        WHERE u.email = :email
                        """)
                .param("email", email)
                .query(String.class)
                .optional();
    }

    private void expire(String token) {
        jdbc().sql("UPDATE password_reset_tokens SET expiry_date = now() - interval '1 hour' WHERE token = :token")
                .param("token", token)
                .update();
    }
}
