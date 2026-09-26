package nl.hackyourfuture.project.jobservice;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-ins for the services that call job-service's {@code /internal/**}, as the monolith's
 * {@code TestServiceCaller} is for it (Day 39): each issuer has a key, served at
 * {@code /<issuer>/service-jwks.json}, and mints tokens with it. One server per JVM, on 127.0.0.1.
 */
final class TestCallers {

    static final String BACKEND = "jobmatch-backend";
    static final String CALLER = "jobmatch-test-caller";
    /** Never trusted. */
    static final String STRANGER = "jobmatch-stranger";

    private static final TestCallers INSTANCE = new TestCallers();
    private static final String AUDIENCE = "jobmatch-internal";

    private final Map<String, RSAKey> keys = new ConcurrentHashMap<>();
    private final RSAKey otherKey = generate();
    private final HttpServer server;

    private TestCallers() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
    }

    static TestCallers instance() {
        return INSTANCE;
    }

    String jwksUrl(String issuer) {
        key(issuer);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + issuer + "/service-jwks.json";
    }

    /** A valid token: this issuer, aud jobmatch-internal, 5 minutes. */
    String token(String issuer) {
        return sign(key(issuer), issuer, AUDIENCE, Duration.ofMinutes(5));
    }

    /** Expired 5 minutes ago, past the 60 s skew. */
    String expired(String issuer) {
        return sign(key(issuer), issuer, AUDIENCE, Duration.ofMinutes(-5));
    }

    String forAudience(String issuer, String audience) {
        return sign(key(issuer), issuer, audience, Duration.ofMinutes(5));
    }

    /** Signed with a key that is not in the issuer's key set. */
    String signedByAnotherKey(String issuer) {
        key(issuer);
        return sign(otherKey, issuer, AUDIENCE, Duration.ofMinutes(5));
    }

    private RSAKey key(String issuer) {
        return keys.computeIfAbsent(issuer, name -> {
            RSAKey key = generate();
            byte[] keySet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/" + name + "/service-jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keySet.length);
                exchange.getResponseBody().write(keySet);
                exchange.close();
            });
            return key;
        });
    }

    private static String sign(RSAKey key, String issuer, String audience, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(issuer)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minus(Duration.ofMinutes(20))))
                .expirationTime(Date.from(now.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString())
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
