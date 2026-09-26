package nl.hackyourfuture.project.jobservice;

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
 * Who may call job-service (Day 17): anyone for job search, the scraper for actuator, and from
 * Track B1 other services, with a service token, for {@code /internal/**}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Actuator, on its own port.
     *
     * <p>{@code EndpointRequest} only matches inside the management context when the
     * management port differs from the application port, so this chain governs the actuator
     * port alone.
     *
     * <p>Ordered ahead of the application chain because the first matching chain wins.
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
     * The public chain (order 2 is reserved for the /internal/** chain added in Track B1).
     *
     * <p>Permits the job search GET routes, the service key set, and error pages. Everything
     * else requires authentication. No user tokens are read here: {@code jobs} never reads a
     * user, and {@code StaleCookieIT} sends stale cookies to {@code /api/jobs} and expects 200.
     *
     * <p>Not {@code denyAll()}: that would turn a trusted 404 or 400 into 403. Not without the
     * key set or {@code /error}: the monolith could not fetch the key set, and any 400 or 500
     * would reach an anonymous caller as 401. This is the access table's third copy, after the
     * gateway's {@code Routes} and the monolith's {@code SecurityConfig} (Day 16).
     */
    @Bean
    @Order(3)
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/jobs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/jobs/filters").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/jobs/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/service-jwks.json").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
