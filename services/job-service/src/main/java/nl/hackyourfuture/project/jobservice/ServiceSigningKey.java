package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key job-service signs its own service tokens with (Day 39): what proves job-service
 * itself to another service, where identity's user key proves a user. A separate key, not the
 * monolith's service key, so the two rotate apart.
 *
 * <p>The key is read from the PEM file {@code SERVICE_JWT_PRIVATE_KEY_FILE} names, with the same
 * checks as the identity's user key: never generated at startup, only loaded from a file, and the
 * application does not start without a usable one.
 *
 * <p>The key id is the public key's RFC 7638 thumbprint, so it cannot drift from the key.
 *
 * <p>The reading is copied from identity's {@code SigningKey}, because job-service is a separate
 * container and sees nothing of {@code backend/}.
 */
@Component
public class ServiceSigningKey {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceSigningKey.class);
    private static final String VARIABLE = "SERVICE_JWT_PRIVATE_KEY_FILE";
    private static final int MIN_BITS = 2048;

    private final RSAKey key;

    public ServiceSigningKey(@Value("${app.service-jwt.private-key-file:}") String file) {
        this.key = read(VARIABLE, file);
        LOGGER.info("Signing service tokens with key {}", key.getKeyID());
    }

    /** The key id tokens carry in their header and the JWKS publishes. */
    public String keyId() {
        return key.getKeyID();
    }

    /** The private key, for signing. Never published. */
    public RSAKey privateJwk() {
        return key;
    }

    /** The public half, the only part that may leave this module. */
    public RSAKey publicJwk() {
        return key.toPublicJWK();
    }

    /**
     * The key in the PEM file {@code variable} names, after every check above; each failure names
     * the variable. How the user key (identity's {@code SigningKey}) is read too, so both keys are held
     * to one standard.
     */
    public static RSAKey read(String variable, String file) {
        if (file == null || file.isBlank()) {
            throw fail(variable, "is not set. Point it at an RSA private key (PEM, PKCS#8), for example one "
                    + "written by scripts/jwt-key.sh");
        }
        return load(variable, Path.of(file));
    }

    private static RSAKey load(String variable, Path file) {
        String pem;
        try {
            pem = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw fail(variable, "points at " + file + ", which cannot be read: " + e);
        }
        RSAPrivateCrtKey privateKey = parse(variable, pem, file);
        if (privateKey.getModulus().bitLength() < MIN_BITS) {
            throw fail(variable, "points at a " + privateKey.getModulus().bitLength() + "-bit key; "
                    + MIN_BITS + " bits is the minimum");
        }
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey unnamed = new RSAKey.Builder(publicKey).build();
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(unnamed.computeThumbprint().toString())
                    .build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw fail(variable, "points at " + file + ", whose public key cannot be derived: " + e);
        }
    }

    private static RSAPrivateCrtKey parse(String variable, String pem, Path file) {
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw fail(variable, "points at " + file + ", which is not a PKCS#8 PEM private key "
                    + "(\"" + begin + "\")");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
            if (KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der))
                    instanceof RSAPrivateCrtKey rsa) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw fail(variable, "points at " + file + ", which is not an RSA private key: " + e.getMessage());
        }
        throw fail(variable, "points at " + file + ", an RSA key without the parameters needed to derive "
                + "its public key");
    }

    private static IllegalStateException fail(String variable, String problem) {
        return new IllegalStateException(variable + " " + problem + ". Tokens cannot be signed "
                + "without it, and the application never generates a key of its own.");
    }
}
