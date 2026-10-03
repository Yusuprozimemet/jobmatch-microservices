package nl.hackyourfuture.project.applicationservice;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import nl.hackyourfuture.project.backend.shared.web.TokenSubject;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.UUID;

/**
 * The user's access token, verified in the service as identity's {@code AccessTokenAuthentication}
 * verifies it in the monolith, but against the key set identity publishes (fetched once and cached
 * by the decoder): issued by {@code jobmatch-identity} for {@code jobmatch-api}.
 */
@Component
public class UserTokens {

    private static final String ISSUER = "jobmatch-identity";
    private static final String AUDIENCE = "jobmatch-api";
    private static final String COOKIE = "access_token";

    private final JwtDecoder decoder;

    public UserTokens(@Value("${app.identity.jwks-url}") String jwksUrl) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(AUDIENCE))));
        this.decoder = nimbus;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    /**
     * The cookie's token, but only one that verifies: a bad cookie reads as no cookie, as in the
     * monolith. The {@code Authorization} header is not read (Day 21): the monolith and the gateway
     * read only the cookie, and the gateway forwards it here.
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
     * The email as the principal and the verified {@code sub} as a {@code TokenSubject} in the
     * details, as the monolith's {@code emailPrincipal}. The saved-jobs controllers (Track E1)
     * read the id only from there.
     */
    public static AbstractAuthenticationToken subjectPrincipal(Jwt jwt) {
        var token = UsernamePasswordAuthenticationToken.authenticated(jwt.getClaimAsString("email"), null, List.of());
        token.setDetails(new TokenSubject(UUID.fromString(jwt.getSubject())));
        return token;
    }

    private static String cookie(HttpServletRequest request) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
