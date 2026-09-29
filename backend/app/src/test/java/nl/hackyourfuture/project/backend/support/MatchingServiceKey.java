package nl.hackyourfuture.project.backend.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * matching-service's test key (Day 21), trusted by the monolith ({@link IntegrationTest}) and the
 * job-service container ({@link JobService}). The key set is served from this JVM, so a context
 * can trust matching-service without starting its container, and the container ({@link MatchingService})
 * signs with this same key from {@link #pemPath()}.
 *
 * <p>One key per JVM, lazily started and synchronized like {@link JobService#ensureStarted()};
 * the key is generated with its RFC 7638 thumbprint as the key id, as the real service publishes
 * its key.
 */
public final class MatchingServiceKey {

    private static final String AUDIENCE = "jobmatch-internal";
    public static final String ISSUER = "jobmatch-matching-service";

    private static volatile HttpServer server;
    private static RSAKey key;
    private static Path pemFile;

    private MatchingServiceKey() {
    }

    /** The key server's port, for {@link org.testcontainers.Testcontainers#exposeHostPorts}. */
    public static int port() {
        ensureStarted();
        return server.getAddress().getPort();
    }

    /** Where this JVM's contexts reach the public key set; a container needs {@link #port()}. */
    public static String jwksUrl() {
        ensureStarted();
        return "http://127.0.0.1:" + port() + "/.well-known/service-jwks.json";
    }

    /** A token minted with matching-service's own key, as if matching-service itself had signed it. */
    public static String mintToken() {
        ensureStarted();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ISSUER)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a token as matching-service", e);
        }
        return token.serialize();
    }

    /** The private key as a PEM file, written once, which MatchingService copies into the container. */
    public static Path pemPath() {
        ensureStarted();
        return pemFile;
    }

    private static synchronized void ensureStarted() {
        if (server != null) {
            return;
        }
        try {
            RSAKey generated = new RSAKeyGenerator(2048).generate();
            // The container publishes its key under the RFC 7638 thumbprint; a token minted with
            // any other key id matches nothing in its key set.
            String keyId = generated.toPublicJWK().computeThumbprint().toString();
            key = new RSAKey.Builder(generated).keyID(keyId).build();
            pemFile = TestSigningKey.write(TestSigningKey.pem(key.toPrivateKey().getEncoded()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate matching-service's test key", e);
        }

        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/.well-known/service-jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }
}
