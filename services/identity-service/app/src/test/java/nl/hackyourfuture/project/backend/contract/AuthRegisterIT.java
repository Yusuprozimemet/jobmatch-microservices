package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/auth/register} — the wire contract.
 *
 * <p>Status, body and cookies only. Nothing here knows that a password is BCrypt, that the
 * account is two rows, or which class writes them, so the JWT rewrite in Phase 2 and the move
 * into identity-service later both leave this file untouched.
 */
class AuthRegisterIT extends IntegrationTest {

    @Test
    void registersAnAccountAndReturns201() {
        ApiResponse response = anonymous().post("/api/auth/register",
                registration("ada@example.test", "Ada Lovelace", "Password123!"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.at("/email").asString()).isEqualTo("ada@example.test");
        assertThat(response.at("/name").asString()).isEqualTo("Ada Lovelace");
        assertThat(response.at("/id").asString()).isNotBlank();
    }

    // Registration is not a login: the frontend sends the user to /login afterwards.
    @Test
    void doesNotLogTheNewAccountIn() {
        var client = anonymous();

        client.post("/api/auth/register", registration("grace@example.test", "Grace", "Password123!"));

        assertThat(client.get("/api/users/me").status()).isEqualTo(401);
    }

    @Test
    void rejectsAnEmailThatIsAlreadyRegistered() {
        anonymous().post("/api/auth/register", registration("taken@example.test", "First", "Password123!"));

        ApiResponse response = anonymous().post("/api/auth/register",
                registration("taken@example.test", "Second", "Password123!"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.at("/detail").asString()).contains("already exists");
    }

    // The email is the identity, so the same address in another case is the same account.
    @Test
    void treatsADifferentlyCasedEmailAsTheSameAccount() {
        anonymous().post("/api/auth/register", registration("mixed@example.test", "First", "Password123!"));

        ApiResponse response = anonymous().post("/api/auth/register",
                registration("MIXED@Example.Test", "Second", "Password123!"));

        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void registeringWithCapitalsCreatesAnAccountThatLogsInLowercase() {
        anonymous().post("/api/auth/register", registration("Alan@Example.Test", "Alan", "Password123!"));

        ApiResponse login = anonymous().post("/api/auth/login",
                Map.of("email", "alan@example.test", "password", "Password123!"));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.at("/email").asString()).isEqualTo("alan@example.test");
    }

    @Test
    void rejectsAPasswordShorterThanSixCharacters() {
        ApiResponse response = anonymous().post("/api/auth/register",
                registration("short@example.test", "Short", "12345"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/password").asString()).isEqualTo("Password must be at least 6 characters");
    }

    // The checkbox is enforced here, not in the browser: no personal data without the agreement.
    @Test
    void rejectsARegistrationThatDeclinesTheTerms() {
        ApiResponse response = anonymous().post("/api/auth/register",
                registration("declined@example.test", "Declined", "Password123!", false));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/acceptedTerms").asString())
                .isEqualTo("You must accept the terms and privacy policy");
    }

    // A missing field has to fail the same way an explicit false does, or leaving it out
    // would be a way around the agreement.
    @Test
    void rejectsARegistrationThatOmitsTheTermsField() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Missing");
        body.put("email", "missing-terms@example.test");
        body.put("password", "Password123!");

        ApiResponse response = anonymous().post("/api/auth/register", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/acceptedTerms").asString())
                .isEqualTo("You must accept the terms and privacy policy");
    }

    @Test
    void rejectsAnAddressThatIsNotAnEmail() {
        ApiResponse response = anonymous().post("/api/auth/register",
                registration("not-an-email", "Nobody", "Password123!"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/email").isMissingNode()).isFalse();
    }

    @Test
    void rejectsABlankName() {
        ApiResponse response = anonymous().post("/api/auth/register",
                registration("nameless@example.test", "  ", "Password123!"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/name").asString()).isEqualTo("Name is required");
    }

    private static Map<String, Object> registration(String email, String name, String password) {
        return registration(email, name, password, true);
    }

    private static Map<String, Object> registration(String email, String name, String password, boolean acceptedTerms) {
        return Map.of("name", name, "email", email, "password", password, "acceptedTerms", acceptedTerms);
    }
}
