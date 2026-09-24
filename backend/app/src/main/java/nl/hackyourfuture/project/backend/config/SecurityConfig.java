package nl.hackyourfuture.project.backend.config;

import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.identity.auth.AuthorizationRequestCookie;
import nl.hackyourfuture.project.backend.identity.auth.OAuth2LoginSuccessHandler;
import nl.hackyourfuture.project.backend.identity.token.AccessTokenAuthentication;
import nl.hackyourfuture.project.backend.identity.token.AuthCookies;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Under /api so the Next.js proxy forwards it.
    private static final String AUTHORIZATION_BASE_URI = "/api/oauth2/authorization";
    private static final String REDIRECTION_BASE_URI = "/api/login/oauth2/code/*";

    /**
     * Actuator, on its own port.
     *
     * <p>{@code EndpointRequest} only matches inside the management context when the
     * management port differs from the application port, so this chain governs the actuator
     * port alone. On the application port every {@code /actuator/**} path falls through to the
     * chain below and its {@code anyRequest().authenticated()}, which is what keeps the
     * metrics off the public surface without a second set of credentials to manage.
     *
     * <p>Ordered ahead of the application chain because the first matching chain wins.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Nothing here reads a cookie, and the scraper does not have one.
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<OAuth2UserService<OidcUserRequest, OidcUser>> oidcUserService,
            OAuth2LoginSuccessHandler oauth2LoginSuccessHandler,
            AuthorizationRequestCookie authorizationRequestCookie,
            AuthCookies authCookies,
            AccessTokenAuthentication accessTokens,
            Environment environment) {
        http
                .authorizeHttpRequests(auth -> auth
                        // This one needs login; the rest of /api/auth/** is public.
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/docs/**").permitAll()
                        .requestMatchers("/api/oauth2/**", "/api/login/oauth2/**").permitAll()
                        // The public key tokens are verified with. Whoever verifies has no token yet.
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // Only these /api/jobs routes are public - top-matches stays private.
                        .requestMatchers(HttpMethod.GET, "/api/jobs/top-matches").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/jobs", "/api/jobs/filters", "/api/jobs/*").permitAll()
                        .anyRequest().authenticated()
                )
                // Return 401 for an unauthenticated API call instead of redirecting to Google,
                // which a browser fetch() can't follow (CORS) and just fails instead.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                // No session holds anything: every request brings its access token (Day 13), and
                // Google sign-in keeps its state in cookies of its own (Day 14).
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(accessTokens.cookieResolver())
                        .jwt(jwt -> jwt
                                .decoder(accessTokens.decoder())
                                .jwtAuthenticationConverter(AccessTokenAuthentication::emailPrincipal))
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Custom logout: revoke the refresh token, clear the cookies and return JSON.
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> authCookies.logout(request, response))
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpStatus.OK.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\": \"Logged out successfully\"}");
                            response.getWriter().flush();
                        })
                );

        // No Google credentials means no Google login - skip setting it up.
        if (clientRegistrations.getIfAvailable() != null) {
            String loginRedirect = environment.getRequiredProperty("app.oauth2.failure-redirect");
            // By default the failure handler creates a session to hold the exception.
            SimpleUrlAuthenticationFailureHandler failureHandler = new SimpleUrlAuthenticationFailureHandler(loginRedirect);
            failureHandler.setAllowSessionCreation(false);
            http.oauth2Login(oauth2 -> oauth2
                    .authorizationEndpoint(endpoint -> endpoint
                            .baseUri(AUTHORIZATION_BASE_URI)
                            .authorizationRequestRepository(authorizationRequestCookie))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(REDIRECTION_BASE_URI))
                    // Named explicitly so the email-verified check can't silently disappear.
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService.getObject()))
                    .successHandler(oauth2LoginSuccessHandler)
                    .failureHandler(failureHandler)
            );
            // Logged for debugging - a mismatched redirect URI is the usual sign-in failure.
            log.info("Google sign-in enabled at {}/google, redirect URI {}",
                    AUTHORIZATION_BASE_URI, environment.getProperty("app.oauth2.google.redirect-uri"));
        } else {
            log.info("Google sign-in disabled: no OAuth2 client credentials configured");
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}