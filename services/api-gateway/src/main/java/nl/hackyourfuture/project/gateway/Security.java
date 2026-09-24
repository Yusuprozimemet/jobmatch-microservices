package nl.hackyourfuture.project.gateway;

import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.Arrays;
import java.util.List;

/**
 * The gateway checks the access token itself (Day 15), as the backend does, so a request that
 * needs a login and has none is answered here and never reaches the backend. The backend keeps
 * checking too: it never trusts the network.
 *
 * <p>The rules are the backend's {@code SecurityConfig}, in its order. The token is the
 * {@code access_token} cookie, verified against the key set the backend publishes (fetched once
 * and cached by the decoder), issued by {@code jobmatch-identity} for {@code jobmatch-api}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class Security {

    static final String ACCESS_COOKIE = "access_token";
    static final String ISSUER = "jobmatch-identity";
    static final String AUDIENCE = "jobmatch-api";

    @Bean
    JwtDecoder accessTokenDecoder(@Value("${gateway.jwks-url}") String jwksUrl) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(AUDIENCE))));
        return nimbus;
    }

    /**
     * Actuator, on the management port only, as in the backend's {@code SecurityConfig}.
     * {@code EndpointRequest} matches inside the management context alone when that port differs
     * from the public one, and only the exposed endpoints; everything else, the public port's
     * {@code /actuator/**} included, falls through to the chain below. Without this one the
     * chain below would answer the scraper 401 too.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain gatewayFilterChain(HttpSecurity http, JwtDecoder decoder) {
        HttpStatusEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/docs/**").permitAll()
                        .requestMatchers("/api/oauth2/**", "/api/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/jobs/top-matches").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/jobs", "/api/jobs/filters", "/api/jobs/*").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(verifiedCookie(decoder))
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(unauthorized))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // The backend's logout is a route like any other; the gateway has none of its own.
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The cookie's token, but only one that verifies, as in the backend's
     * {@code AccessTokenAuthentication}: a stale cookie reads as none, so login, refresh and the
     * public routes still work for a browser holding one.
     */
    static BearerTokenResolver verifiedCookie(JwtDecoder decoder) {
        return request -> {
            String token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                    .filter(cookie -> ACCESS_COOKIE.equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(null);
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
}
