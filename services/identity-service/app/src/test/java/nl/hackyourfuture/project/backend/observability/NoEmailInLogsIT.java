package nl.hackyourfuture.project.backend.observability;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user's email is personal data and identity's logs name a user only by id (Day 42, H28.14).
 */
@ExtendWith(OutputCaptureExtension.class)
class NoEmailInLogsIT extends IntegrationTest {

    @Test
    void noLineLoggedNamesTheAccountsEmail(CapturedOutput output) throws InterruptedException {
        TestUser user = aUser().create();
        int before = output.getAll().length();
        assertThat(anonymous().post("/api/auth/forgot-password", Map.of("email", user.email()))
                .status()).isEqualTo(200);

        // Wait for the asynchronous send (EmailService.sendPasswordResetEmail is @Async;
        // the test profile's SMTP at localhost:1025 refuses, so it logs a failure).
        long deadline = System.currentTimeMillis() + 10_000;
        while (!sendLogged(output, before) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(sendLogged(output, before))
                .as("the asynchronous send logged its outcome").isTrue();

        assertThat(authenticatedAs(user).patch("/api/auth/password",
                Map.of("currentPassword", user.password(), "newPassword", "Another-Password-1"))
                .status()).isEqualTo(200);

        assertThat(output.getAll().lines()
                .filter(line -> line.contains(user.email()))
                .toList())
                .as("lines logged that name " + user.email())
                .isEmpty();
    }

    private static boolean sendLogged(CapturedOutput output, int before) {
        return output.getAll().substring(before).toLowerCase(Locale.ROOT).contains("reset email");
    }
}
