package nl.hackyourfuture.project.backend.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * The key the application signs tokens with in a test run: made once per JVM and written to a
 * temporary PEM file, so no key is ever committed (Day 12).
 *
 * <p>The application refuses to start without one, so {@link IntegrationTest} passes this path
 * to every context it starts, the way it passes the database's address.
 */
public final class TestSigningKey {

    private static Path file;

    private TestSigningKey() {
    }

    /** A 2048-bit RSA private key as a PKCS#8 PEM file, the format the application reads. */
    public static synchronized Path path() {
        if (file == null) {
            file = write(pem(rsaKeyPair(2048).getPrivate().getEncoded()));
        }
        return file;
    }

    /** A new RSA key pair of the given size, for tests that need a key other than this one. */
    public static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCS#8 bytes in the PEM armour {@code openssl genpkey} writes. */
    public static String pem(byte[] pkcs8) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pkcs8);
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /** Writes text to a temporary file removed when the JVM exits. */
    public static Path write(String content) {
        try {
            Path path = Files.createTempFile("jwt-signing-key", ".pem");
            path.toFile().deleteOnExit();
            return Files.writeString(path, content, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
