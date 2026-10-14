package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend does not take a user id from a header (Day 15, Track 0). From Day 15 the gateway
 * sets {@code X-User-Id} from a verified token; the backend still reads only the token, so a
 * request that reaches it with the header alone, past the gateway or around it, is anonymous.
 *
 * <p>Direct to the backend, always: through a gateway that strips the header this could not fail.
 */
class UserIdHeaderIT extends IntegrationTest {

    static final String USER_ID = "X-User-Id";

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/profile"})
    void aRealUsersIdInTheHeaderIsNotALogin(String path) {
        TestUser user = aUser().create();
        assertThat(authenticatedAs(user).get(path).status()).as("signed in, the route answers").isEqualTo(200);

        assertThat(direct().withHeader(USER_ID, user.id().toString()).get(path).status()).isEqualTo(401);
    }
}
