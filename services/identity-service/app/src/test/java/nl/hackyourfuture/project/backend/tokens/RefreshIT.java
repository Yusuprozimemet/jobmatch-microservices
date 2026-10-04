package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jwt.SignedJWT;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestTokens;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.HttpCookie;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/auth/refresh} trades a live refresh cookie for a new pair, once (Day 13).
 *
 * <p>The request carries an expired access token, as a browser's does when it refreshes.
 */
class RefreshIT extends IntegrationTest {

    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";

    @Autowired
    private SigningKey signingKey;

    @Test
    void anExpiredAccessCookieAndALiveRefreshCookieGetANewPair() throws Exception {
        TestUser user = aUser().create();
        String refresh = authenticatedAs(user).cookies().get(REFRESH);

        ApiResponse response = browserHolding(user, refresh).post("/api/auth/refresh", null);

        assertThat(response.status()).isEqualTo(200);
        String access = value(response.setCookie(ACCESS));
        assertThat(SignedJWT.parse(access).getJWTClaimsSet().getSubject()).isEqualTo(user.id().toString());
        assertThat(value(response.setCookie(REFRESH))).isNotBlank().isNotEqualTo(refresh);
        assertThat(anonymous().withCookie(ACCESS, access).get("/api/users/me").at("/email").asString())
                .as("the new access cookie signs the user in").isEqualTo(user.email());
    }

    @Test
    void aRefreshTokenWorksOnce() {
        TestUser user = aUser().create();
        String refresh = authenticatedAs(user).cookies().get(REFRESH);
        assertThat(browserHolding(user, refresh).post("/api/auth/refresh", null).status()).isEqualTo(200);

        ApiResponse again = browserHolding(user, refresh).post("/api/auth/refresh", null);

        assertThat(again.status()).isEqualTo(401);
        assertThat(Cookies.deletes(again.setCookie(ACCESS))).as("deletes the access cookie").isTrue();
        assertThat(Cookies.deletes(again.setCookie(REFRESH))).as("deletes the refresh cookie").isTrue();
    }

    private ApiClient browserHolding(TestUser user, String refresh) {
        return anonymous()
                .withCookie(ACCESS, TestTokens.expired(signingKey, user.id(), user.email()))
                .withCookie(REFRESH, refresh);
    }

    private static String value(String setCookieHeader) {
        return HttpCookie.parse(setCookieHeader).getFirst().getValue();
    }
}
