package nl.hackyourfuture.project.matchingservice;

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
 * A stand-in for identity's user key set, as {@code TestCallers} is on job-service for service keys:
 * one key, served at {@code /.well-known/jwks.json}, and tokens minted with it as identity's
 * {@code AccessTokens} mints them. One server per JVM, on 127.0.0.1.
 */
final class TestIdentity {

    private static final TestIdentity INSTANCE = new TestIdentity();

    private final RSAKey key = generate();
    /** Never published. */
    private final RSAKey otherKey = generate();
    private final HttpServer server;

    private TestIdentity() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] keySet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keySet.length);
            exchange.getResponseBody().write(keySet);
            exchange.close();
        });
        server.start();
    }

    static TestIdentity instance() {
        return INSTANCE;
    }

    /** identity's URL; the key set is at the path the service appends. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A valid token: iss jobmatch-identity, aud jobmatch-api, 5 minutes. */
    String token(UUID userId) {
        return token(userId, "jobmatch-identity", "jobmatch-api", Duration.ofMinutes(5));
    }

    String token(UUID userId, String issuer, String audience, Duration lifetime) {
        return sign(key, userId, issuer, audience, lifetime);
    }

    String signedByAnotherKey(UUID userId) {
        return sign(otherKey, userId, "jobmatch-identity", "jobmatch-api", Duration.ofMinutes(5));
    }

    private static String sign(RSAKey key, UUID userId, String issuer, String audience, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("email", userId + "@example.com")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(20))))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            token.sign(new RSASSASigner(key));
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
}
