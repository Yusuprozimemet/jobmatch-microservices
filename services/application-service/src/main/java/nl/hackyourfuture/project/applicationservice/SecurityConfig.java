package nl.hackyourfuture.project.applicationservice;

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
 * Who may call application-service (Day 25): the scraper for actuator, other services for its
 * service key set and saved-count requests with service tokens, and logged-in users for saved jobs.
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
     * Service tokens only (Day 25): the bearer header never the cookie. CSRF off because a bearer
     * token is not sent by a browser on its own. Ordered ahead of the user chain because the first
     * matching chain wins.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain internalFilterChain(HttpSecurity http, InternalCallers callers) throws Exception {
        http
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(rs -> rs
                        .authenticationManagerResolver(callers.resolver())
                        // No bearerTokenResolver: the default reads only the Authorization header,
                        // so the user's access_token cookie is ignored here as intended.
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * The user chain. Every route needs the user's access token (the cookie, verified by {@code UserTokens})
     * except the service key set and {@code /error}, without which a 400 or 500 would reach the caller as 401.
     * The key set is fetched by identity and job-service to verify application-service's tokens.
     */
    @Bean
    @Order(3)
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
