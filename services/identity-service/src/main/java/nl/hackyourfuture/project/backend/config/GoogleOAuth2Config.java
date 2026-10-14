package nl.hackyourfuture.project.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

// Only registers Google if a client id is set, so startup doesn't fail without one.
@Configuration
@ConditionalOnExpression("'${app.oauth2.google.client-id:}'.trim().length() > 0")
public class GoogleOAuth2Config {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.oauth2.google.client-id}") String clientId,
            @Value("${app.oauth2.google.client-secret}") String clientSecret,
            @Value("${app.oauth2.google.redirect-uri}") String redirectUri) {

        // Google's standard endpoints and scopes.
        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(redirectUri)
                .build();

        return new InMemoryClientRegistrationRepository(google);
    }

    // Blocks an unverified email before login succeeds, so it can't claim an existing account.
    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser user = delegate.loadUser(userRequest);
            if (!Boolean.TRUE.equals(user.getEmailVerified()) || user.getEmail() == null) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("email_not_verified"),
                        "Google account has no verified email address");
            }
            return user;
        };
    }
}
