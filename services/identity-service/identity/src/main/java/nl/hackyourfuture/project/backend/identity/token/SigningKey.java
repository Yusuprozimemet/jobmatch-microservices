package nl.hackyourfuture.project.backend.identity.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
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
 * The RSA key {@code identity} signs tokens with (Day 12), read from the PEM file
 * {@code JWT_PRIVATE_KEY_FILE} names, or from the PEM in {@code JWT_PRIVATE_KEY} itself: ECS
 * hands a secret to a task as a variable, not a file (Day 33). Exactly one of the two is set.
 *
 * <p>Never generated here, in any profile: a key made at startup would sign every user out on
 * every restart. Without a usable file the application does not start, and says which variable
 * to set. Compose writes one with {@code scripts/jwt-key.sh}; the test harness makes its own.
 *
 * <p>The key id is the public key's RFC 7638 thumbprint, so it cannot drift from the key.
 */
@Slf4j
@Component
public class SigningKey {

    static final String VARIABLE = "JWT_PRIVATE_KEY_FILE";
    static final String CONTENT_VARIABLE = "JWT_PRIVATE_KEY";
    private static final int MIN_BITS = 2048;

    private final RSAKey key;

    public SigningKey(@Value("${app.jwt.private-key-file:}") String file,
                      @Value("${app.jwt.private-key:}") String content) {
        this.key = read(VARIABLE, file, CONTENT_VARIABLE, content);
        log.info("Signing tokens with key {}", key.getKeyID());
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
     * The key in the PEM file {@code variable} names, or in {@code contentVariable} itself, after
     * every check above; each failure names the variable, and none repeats the content. How the
     * service key (Day 39) is read too, so both keys are held to one standard.
     */
    public static RSAKey read(String variable, String file, String contentVariable, String content) {
        boolean hasFile = file != null && !file.isBlank();
        boolean hasContent = content != null && !content.isBlank();
        if (hasFile && hasContent) {
            throw fail(variable + " and " + contentVariable + " are both set. Set only one");
        }
        if (!hasFile && !hasContent) {
            throw fail("Neither " + variable + " nor " + contentVariable + " is set. Point " + variable
                    + " at an RSA private key (PEM, PKCS#8), for example one written by scripts/jwt-key.sh, "
                    + "or put the PEM itself in " + contentVariable);
        }
        if (hasContent) {
            return load(contentVariable, content, "holds", "a value");
        }
        Path path = Path.of(file);
        try {
            return load(variable, Files.readString(path, StandardCharsets.US_ASCII), "points at", path.toString());
        } catch (IOException e) {
            throw fail(variable, "points at " + file + ", which cannot be read: " + e);
        }
    }

    /** {@code variable} {@code verb} {@code source}: "points at /run/key.pem", or "holds a value". */
    private static RSAKey load(String variable, String pem, String verb, String source) {
        String where = verb + " " + source;
        RSAPrivateCrtKey privateKey = parse(variable, pem, where);
        if (privateKey.getModulus().bitLength() < MIN_BITS) {
            throw fail(variable, verb + " a " + privateKey.getModulus().bitLength() + "-bit key; "
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
            throw fail(variable, where + ", whose public key cannot be derived: " + e);
        }
    }

    private static RSAPrivateCrtKey parse(String variable, String pem, String where) {
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw fail(variable, where + ", which is not a PKCS#8 PEM private key "
                    + "(\"" + begin + "\")");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
            if (KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der))
                    instanceof RSAPrivateCrtKey rsa) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw fail(variable, where + ", which is not an RSA private key: " + e.getMessage());
        }
        throw fail(variable, where + ", an RSA key without the parameters needed to derive "
                + "its public key");
    }

    private static IllegalStateException fail(String variable, String problem) {
        return fail(variable + " " + problem);
    }

    private static IllegalStateException fail(String problem) {
        return new IllegalStateException(problem + ". Tokens cannot be signed "
                + "without it, and the application never generates a key of its own.");
    }
}
