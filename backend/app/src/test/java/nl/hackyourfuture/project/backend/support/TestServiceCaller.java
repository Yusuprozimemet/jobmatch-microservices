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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * A stand-in for another service, for testing /internal/** security (Day 39). It serves its public
 * JWKS at /.well-known/service-jwks.json over HTTP, as Day 17's job-service will, and mints tokens
 * signed by its own key, so the trusted-issuer list is checked against a real key set over HTTP.
 *
 * <p>One instance per JVM, started once, shared by every test context. Tests in a class run one at a
 * time, so resetting is not required; running these classes in parallel would need it.
 */
public final class TestServiceCaller {

    private static final TestServiceCaller INSTANCE = new TestServiceCaller();

    public static final String ISSUER = "jobmatch-test-caller";
    private static final String AUDIENCE = "jobmatch-internal";

    private final RSAKey key;
    private final RSAKey otherKey;
    private final HttpServer server;

    private TestServiceCaller() {
        this.key = generate();
        this.otherKey = generate();
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] keySet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/.well-known/service-jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keySet.length);
            exchange.getResponseBody().write(keySet);
            exchange.close();
        });
        server.start();
    }

    public static TestServiceCaller instance() {
        return INSTANCE;
    }

    /** Point {@code app.internal.trusted-issuers[jobmatch-test-caller]} here. */
    public String jwksUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/service-jwks.json";
    }

    /** A valid token: iss ISSUER, aud jobmatch-internal, 5 minutes. */
    public String token() {
        return sign(key, ISSUER, AUDIENCE, Duration.ofMinutes(5));
    }

    /** A token issued 10 minutes ago and expired 5 minutes ago, past the 60 s skew. */
    public String expired() {
        return sign(key, ISSUER, AUDIENCE, Duration.ofMinutes(-5));
    }

    /** A token for a different audience. */
    public String forAudience(String audience) {
        return sign(key, ISSUER, audience, Duration.ofMinutes(5));
    }

    /** A token signed with a key not in the published key set. */
    public String signedByAnotherKey() {
        return sign(otherKey, ISSUER, AUDIENCE, Duration.ofMinutes(5));
    }

    /** A token with a different issuer, signed by this caller's key. */
    public String withIssuer(String issuer) {
        return sign(key, issuer, AUDIENCE, Duration.ofMinutes(5));
    }

    private static String sign(RSAKey signingKey, String issuer, String audience, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(issuer)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(20))))
                .expirationTime(Date.from(now.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        try {
            token.sign(new RSASSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a test service token", e);
        }
        return token.serialize();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a test RSA key", e);
        }
    }
}
