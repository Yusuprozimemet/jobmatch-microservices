package nl.hackyourfuture.project.matchingservice;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Who may call matching-service (Day 21): the scraper for actuator, other services for its service
 * key set, and logged-in users for top-matches. No {@code /internal/**} chain: matching-service serves
 * no internal route, and nothing calls it but the gateway.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Actuator, on its own port: {@code EndpointRequest} matches only inside the management
     * context when the ports differ. Ordered first because the first matching chain wins.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Nothing here reads a cookie, and the scraper does not have one.
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The user chain. Every route needs the user's access token (the cookie, verified by {@code UserTokens})
     * except the service key set and {@code /error}, without which a 400 or 500 would reach the caller as 401.
     * The key set is fetched by identity and job-service to verify matching-service's tokens.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain userFilterChain(HttpSecurity http, UserTokens tokens) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/.well-known/service-jwks.json").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(tokens.cookieResolver())
                        .jwt(jwt -> jwt.decoder(tokens.decoder()).jwtAuthenticationConverter(UserTokens::subjectPrincipal))
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
