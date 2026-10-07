package nl.hackyourfuture.project.backend.identity.token;

import com.nimbusds.jose.JOSEException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * How a request authenticates from Day 13: the {@code access_token} cookie, verified against the
 * key {@code identity} signs with, in process. What {@code SecurityConfig} hands the resource
 * server.
 */
@Component
public class AccessTokenAuthentication {

    private final JwtDecoder decoder;

    public AccessTokenAuthentication(SigningKey key) throws JOSEException {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey(key.publicJwk().toRSAPublicKey()).build();
        // The default checks only the timestamps; ours were issued by us, for the API.
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(AccessTokens.ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(AccessTokens.AUDIENCE))));
        this.decoder = nimbus;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    /**
     * The cookie's token, but only one that verifies. A cookie that has expired or been tampered
     * with reads as no cookie: a route anyone may use answers as it does for anyone, and one that
     * needs a login answers 401, as it does for anyone. Handing the resource server a bad token
     * instead would make it answer 401 before any route's permit applies, login and refresh
     * included. The price is verifying a good token twice.
     */
    public BearerTokenResolver cookieResolver() {
        return request -> {
            String token = cookie(request);
            if (token == null) {
                return null;
            }
            try {
                decoder.decode(token);
                return token;
            } catch (JwtException e) {
                return null;
            }
        };
    }

    /**
     * The token's email as the principal, the plain string the session put there before, so
     * {@code PrincipalEmail} and every controller that reads it are unchanged.
     */
    public static AbstractAuthenticationToken emailPrincipal(Jwt jwt) {
        return UsernamePasswordAuthenticationToken.authenticated(jwt.getClaimAsString("email"), null, List.of());
    }

    private static String cookie(HttpServletRequest request) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> AuthCookies.ACCESS.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
