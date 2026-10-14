package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestSigningKey;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * identity calls no service, so it starts with no service key, serves no service key set,
 * and refuses a token with its old issuer (Day 42, C42.4).
 */
class RetiredServiceIssuerIT extends IntegrationTest {

    private static final String OLD_ISSUER = "jobmatch-backend";

    @Autowired
    private Environment environment;

    @Test
    void startsWithNoServiceKey() {
        assertThat(environment.getProperty("app.service-jwt.private-key-file"))
                .as("the harness sets no service key and identity reads none")
                .isNull();
        assertThat(System.getenv("SERVICE_JWT_PRIVATE_KEY_FILE")).isNull();
    }

    @Test
    void theServiceKeySetIsGone() {
        assertThat(direct().get("/.well-known/service-jwks.json").status())
                .as("no permit is left for the path; anyRequest().authenticated() answers")
                .isEqualTo(401);
    }

    @Test
    void itsOldIssuersTokenIs401() {
        // The key is the one identity signed its service tokens with until Day 42, so
        // only the trust list refuses it.
        TestUser user = aUser().create();
        String token = oldIssuersToken();

        assertThat(direct().withHeader("Authorization", "Bearer " + token)
                .get("/internal/users/" + user.id()).status())
                .isEqualTo(401);
    }

    private static String oldIssuersToken() {
        try {
            String pemContent = Files.readString(TestSigningKey.servicePath(),
                    StandardCharsets.UTF_8);
            String base64 = pemContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] privateKeyBytes = Base64.getDecoder().decode(base64);
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(OLD_ISSUER)
                    .subject(OLD_ISSUER)
                    .audience("jobmatch-internal")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                    .jwtID(UUID.randomUUID().toString())
                    .build();

            SignedJWT token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
            token.sign(new RSASSASigner(privateKey));

            return token.serialize();
        } catch (IOException | GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Could not sign a token with the retired service key", e);
        }
    }
}
