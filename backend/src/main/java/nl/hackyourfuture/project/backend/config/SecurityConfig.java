package nl.hackyourfuture.project.backend.config;

import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.auth.OAuth2LoginSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<OAuth2UserService<OidcUserRequest, OidcUser>> oidcUserService,
            OAuth2LoginSuccessHandler oauth2LoginSuccessHandler,
            Environment environment) {
        http
                .authorizeHttpRequests(auth -> auth
                        // This one needs login; the rest of /api/auth/** is public.
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/docs/**").permitAll()
                        .requestMatchers("/api/oauth2/**", "/api/login/oauth2/**").permitAll()
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
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Custom logout: clear the session cookie and return JSON.
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
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
            http.oauth2Login(oauth2 -> oauth2
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(AUTHORIZATION_BASE_URI))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(REDIRECTION_BASE_URI))
                    // Named explicitly so the email-verified check can't silently disappear.
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService.getObject()))
                    .successHandler(oauth2LoginSuccessHandler)
                    .failureHandler(new SimpleUrlAuthenticationFailureHandler(loginRedirect))
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