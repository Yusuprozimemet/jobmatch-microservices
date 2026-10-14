package nl.hackyourfuture.project.backend.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * A stand-in for Google: an OpenID Connect provider that signs real ID tokens.
 *
 * <p>Two endpoints are enough, because the tests never let the browser reach the
 * authorization endpoint - they read {@code state} and {@code nonce} off the redirect and call
 * the callback themselves:
 *
 * <ul>
 *   <li>{@code POST /token} - exchanges any code for a signed ID token;</li>
 *   <li>{@code GET /jwks} - the public key, which the application fetches to verify it.</li>
 * </ul>
 *
 * <p>Signing for real rather than mocking a bean is the point: the ID token goes through the
 * application's own decoder, so the signature, the issuer, the audience, the expiry and the
 * nonce are all checked by the code that checks them in production. A stub that returned an
 * {@code OidcUser} directly would skip every one of those.
 *
 * <p>One instance per JVM, and {@link #willIssue} mutates it. Tests in a class run one at a
 * time, so that is safe here; running these classes in parallel would not be.
 */
public final class StubOidcProvider {

    /** The client id the application is configured with, and the audience of every ID token. */
    public static final String CLIENT_ID = "stub-client";

    public static final String CLIENT_SECRET = "stub-secret";

    private static final StubOidcProvider INSTANCE = new StubOidcProvider();

    private final HttpServer server;
    private final RSAKey signingKey;

    private volatile String nextIdToken;

    private StubOidcProvider() {
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("stub-key").generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the stub OIDC provider", e);
        }
        server.createContext("/token", this::handleToken);
        server.createContext("/jwks", this::handleJwks);
        server.start();
    }

    /** Started, and the same instance for every test in the run. */
    public static StubOidcProvider instance() {
        return INSTANCE;
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Never actually fetched: the tests read the redirect rather than following it. */
    public String authorizationUri() {
        return issuer() + "/authorize";
    }

    public String tokenUri() {
        return issuer() + "/token";
    }

    public String jwkSetUri() {
        return issuer() + "/jwks";
    }

    /**
     * The identity the next code exchange hands back.
     *
     * <p>{@code nonce} is the value the application put in the authorization redirect. An ID
     * token that echoes something else is rejected, which is what stops a token minted for one
     * sign-in from being replayed into another.
     */
    public void willIssue(String subject, String email, boolean emailVerified, String name, String nonce) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(subject)
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("name", name)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the stub ID token", e);
        }
        this.nextIdToken = jwt.serialize();
    }

    // One token per willIssue call, the way an authorization code can only be exchanged once.
    private void handleToken(HttpExchange exchange) throws IOException {
        String idToken = nextIdToken;
        nextIdToken = null;
        if (idToken == null) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        respond(exchange, 200, """
                {"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600,\
                "id_token":"%s"}""".formatted(idToken));
    }

    private void handleJwks(HttpExchange exchange) throws IOException {
        respond(exchange, 200, new JWKSet(List.of(signingKey.toPublicJWK())).toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
