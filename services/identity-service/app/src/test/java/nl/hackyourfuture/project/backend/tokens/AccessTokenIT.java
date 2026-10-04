package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An access token {@code identity} mints verifies against the key {@code GET /.well-known/jwks.json}
 * publishes, and carries the claims Day 13 and the gateway will rely on (Day 12).
 *
 * <p>Not a contract test: it calls the minting bean, because nothing issues a token over HTTP until
 * Day 13. The key it verifies with comes over HTTP, as a verifier would fetch it.
 */
class AccessTokenIT extends IntegrationTest {

    private static final UUID USER_ID = UUID.fromString("7a1c1a5e-0c8f-4f0e-9d6b-2f1d3e4c5b6a");
    private static final String EMAIL = "ada@example.com";

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void verifiesAgainstThePublishedKeyChosenByItsKid() throws Exception {
        SignedJWT token = SignedJWT.parse(accessTokens.mint(USER_ID, EMAIL));

        assertThat(token.verify(new RSASSAVerifier(publishedKey(token)))).isTrue();
    }

    @Test
    void doesNotVerifyOnceItsSignatureIsChanged() throws Exception {
        SignedJWT token = SignedJWT.parse(accessTokens.mint(USER_ID, EMAIL));
        byte[] signature = token.getSignature().decode();
        signature[signature.length / 2] ^= 0x01;
        SignedJWT tampered = new SignedJWT(token.getHeader().toBase64URL(),
                token.getPayload().toBase64URL(), Base64URL.encode(signature));

        assertThat(tampered.verify(new RSASSAVerifier(publishedKey(tampered)))).isFalse();
    }

    @Test
    void carriesTheUsersIdAsSubjectAndTheAgreedClaims() throws Exception {
        SignedJWT token = SignedJWT.parse(accessTokens.mint(USER_ID, EMAIL));
        JWTClaimsSet claims = token.getJWTClaimsSet();

        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString()).isNotEqualTo(EMAIL);
        assertThat(claims.getStringClaim("email")).isEqualTo(EMAIL);
        assertThat(claims.getIssuer()).isEqualTo("jobmatch-identity");
        assertThat(claims.getAudience()).containsExactly("jobmatch-api");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void twoTokensForOneUserInTheSameSecondDiffer() throws Exception {
        // RS256 is deterministic and iat is in whole seconds: only the jti keeps these apart.
        SignedJWT first;
        SignedJWT second;
        do {
            first = SignedJWT.parse(accessTokens.mint(USER_ID, EMAIL));
            second = SignedJWT.parse(accessTokens.mint(USER_ID, EMAIL));
        } while (!first.getJWTClaimsSet().getIssueTime().equals(second.getJWTClaimsSet().getIssueTime()));

        assertThat(second.serialize()).isNotEqualTo(first.serialize());
    }

    private RSAKey publishedKey(SignedJWT token) throws Exception {
        String body = anonymous().get("/.well-known/jwks.json").body();
        RSAKey key = (RSAKey) JWKSet.parse(body).getKeyByKeyId(token.getHeader().getKeyID());
        assertThat(key).as("a published key with kid %s", token.getHeader().getKeyID()).isNotNull();
        return key;
    }
}
