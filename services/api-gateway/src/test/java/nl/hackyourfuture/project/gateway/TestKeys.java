package nl.hackyourfuture.project.gateway;

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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * The backend's side of tokens, for the gateway's tests: a key set served over HTTP as the
 * backend serves {@code /.well-known/jwks.json}, and tokens signed with its key, or deliberately
 * with another one.
 */
final class TestKeys implements AutoCloseable {

    private final RSAKey key = generate();
    private final RSAKey otherKey = generate();
    private final HttpServer server;

    TestKeys() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] keySet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keySet.length);
            exchange.getResponseBody().write(keySet);
            exchange.close();
        });
        server.start();
    }

    String jwksUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** A token as identity issues one: fifteen minutes, for the API. */
    String valid(UUID userId) {
        return sign(key, userId, Security.ISSUER, Security.AUDIENCE, Duration.ofMinutes(15));
    }

    /** Stale in each way the gateway must refuse. */
    String stale(String kind) {
        UUID someone = UUID.randomUUID();
        return switch (kind) {
            case "expired" -> sign(key, someone, Security.ISSUER, Security.AUDIENCE, Duration.ofMinutes(-5));
            case "another key" -> sign(otherKey, someone, Security.ISSUER, Security.AUDIENCE, Duration.ofMinutes(15));
            case "another audience" -> sign(key, someone, Security.ISSUER, "another-api", Duration.ofMinutes(15));
            case "another issuer" -> sign(key, someone, "someone-else", Security.AUDIENCE, Duration.ofMinutes(15));
            case "garbage" -> "not-a-token";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String sign(RSAKey signingKey, UUID subject, String issuer, String audience, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .claim("email", subject + "@example.test")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(20))))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        try {
            token.sign(new RSASSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return token.serialize();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
