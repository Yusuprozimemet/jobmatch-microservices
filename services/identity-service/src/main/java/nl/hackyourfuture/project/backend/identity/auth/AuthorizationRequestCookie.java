package nl.hackyourfuture.project.backend.identity.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a Google sign-in's authorization request ({@code state}, the nonce, the redirect URI and
 * the PKCE verifier) in a cookie between the start and the callback, instead of a server-side
 * session (Day 14).
 *
 * <p>The value is a JWS signed with the key access tokens are signed with, over the fields the
 * callback needs, expiring with the cookie. Nothing in it is used before the signature and the
 * expiry are verified; a missing, altered or expired cookie loads nothing, and the sign-in fails
 * as a forged {@code state} does.
 */
@Slf4j
@Component
public class AuthorizationRequestCookie implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String COOKIE = "google_auth_request";
    public static final Duration LIFETIME = Duration.ofMinutes(5);

    // Only the callback reads it. Lax, not Strict: Google's redirect back is a cross-site navigation.
    static final String PATH = "/api/login/oauth2/code";

    private static final JOSEObjectType TYPE = new JOSEObjectType("google-auth-request");

    private final SigningKey key;
    private final RSASSASigner signer;
    private final RSASSAVerifier verifier;
    private final boolean secure;

    // Secure as the session cookie that carried this request before (SESSION_COOKIE_SECURE).
    public AuthorizationRequestCookie(SigningKey key,
                                      @Value("${server.servlet.session.cookie.secure:false}") boolean secure)
            throws JOSEException {
        this.key = key;
        this.signer = new RSASSASigner(key.privateJwk());
        this.verifier = new RSASSAVerifier(key.publicJwk());
        this.secure = secure;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        return cookieValue(request)
                .flatMap(this::verified)
                .filter(authorizationRequest -> state.equals(authorizationRequest.getState()))
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        addCookie(response, sign(authorizationRequest), LIFETIME);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (cookieValue(request).isPresent()) {
            addCookie(response, "", Duration.ZERO);
        }
        return authorizationRequest;
    }

    private String sign(OAuth2AuthorizationRequest authorizationRequest) {
        // Whole seconds, as the claims carry them. The request URI is left out: the builder
        // derives it from the rest, and the callback does not read it.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .claim("authorizationUri", authorizationRequest.getAuthorizationUri())
                .claim("clientId", authorizationRequest.getClientId())
                .claim("redirectUri", authorizationRequest.getRedirectUri())
                .claim("scopes", List.copyOf(authorizationRequest.getScopes()))
                .claim("state", authorizationRequest.getState())
                .claim("additionalParameters", authorizationRequest.getAdditionalParameters())
                .claim("attributes", authorizationRequest.getAttributes())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(TYPE)
                .keyID(key.keyId())
                .build();
        SignedJWT token = new SignedJWT(header, claims);
        try {
            token.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the authorization request with key " + key.keyId(), e);
        }
        return token.serialize();
    }

    private Optional<OAuth2AuthorizationRequest> verified(String value) {
        try {
            SignedJWT token = SignedJWT.parse(value);
            if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())
                    || !TYPE.equals(token.getHeader().getType())
                    || !token.verify(verifier)) {
                log.info("Google sign-in callback with an authorization request cookie that fails its signature");
                return Optional.empty();
            }
            JWTClaimsSet claims = token.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(Instant.now())) {
                log.info("Google sign-in callback with an expired authorization request cookie");
                return Optional.empty();
            }
            Map<String, Object> additionalParameters = claims.getJSONObjectClaim("additionalParameters");
            Map<String, Object> attributes = claims.getJSONObjectClaim("attributes");
            return Optional.of(OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(claims.getStringClaim("authorizationUri"))
                    .clientId(claims.getStringClaim("clientId"))
                    .redirectUri(claims.getStringClaim("redirectUri"))
                    .scopes(new HashSet<>(claims.getStringListClaim("scopes")))
                    .state(claims.getStringClaim("state"))
                    .additionalParameters(additionalParameters == null ? Map.of() : additionalParameters)
                    .attributes(attributes == null ? Map.of() : attributes)
                    .build());
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            log.info("Google sign-in callback with an unreadable authorization request cookie: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> cookieValue(HttpServletRequest request) {
        return Optional.ofNullable(request.getCookies()).stream()
                .flatMap(Arrays::stream)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
