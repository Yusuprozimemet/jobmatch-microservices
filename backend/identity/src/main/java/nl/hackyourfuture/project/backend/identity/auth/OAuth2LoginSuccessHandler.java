package nl.hackyourfuture.project.backend.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.identity.token.AuthCookies;
import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

// Turns a Google identity into an account, logs it in, and redirects to the frontend.
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String PROVIDER_GOOGLE = "GOOGLE";

    private final UserRepository userRepository;
    private final AuthCookies authCookies;
    private final PendingGoogleLinks pendingGoogleLinks;

    // Derived from app.base-url in application.yaml.
    @Value("${app.oauth2.success-redirect}")
    private String successRedirect;

    @Value("${app.oauth2.link-required-redirect}")
    private String linkRequiredRedirect;

    @Value("${app.oauth2.terms-required-redirect}")
    private String termsRequiredRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        String email = oidcUser.getEmail() != null ? oidcUser.getEmail().toLowerCase(Locale.ROOT) : null;
        String providerId = oidcUser.getSubject();
        String name = Optional.ofNullable(oidcUser.getFullName()).orElse(email);

        Optional<User> user = resolveUser(email, name, providerId);
        if (user.isEmpty()) {
            // Park it for the email's account - not signed in, since nothing here proves it is theirs.
            userRepository.getUserByEmail(email)
                    .ifPresent(owner -> pendingGoogleLinks.park(response, owner.getId(), providerId));
            log.info("Google sign-in for {} needs the account password before linking", email);
            response.sendRedirect(linkRequiredRedirect);
            return;
        }

        authCookies.issue(response, user.get().getId(), user.get().getEmail());

        // Google skips our terms screen, so send them there first.
        if (user.get().getTermsAcceptedAt() == null) {
            log.info("Google sign-in for {} still needs the terms and privacy agreement", email);
            response.sendRedirect(termsRequiredRedirect);
            return;
        }
        response.sendRedirect(successRedirect);
    }

    // Empty if the email is already taken - an email match alone isn't proof of ownership,
    // so AuthenticationService.login finishes the link instead.
    private Optional<User> resolveUser(String email, String name, String providerId) {
        Optional<User> linked = userRepository.findByProvider(PROVIDER_GOOGLE, providerId);
        if (linked.isPresent()) {
            return linked;
        }
        if (userRepository.getUserByEmail(email).isPresent()) {
            return Optional.empty();
        }

        User created = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .name(name)
                .build();
        try {
            userRepository.createProviderUser(created, PROVIDER_GOOGLE, providerId);
        } catch (DuplicateKeyException ex) {
            // Lost a race with a concurrent sign-up - that account must prove itself too.
            log.info("Concurrent sign-up for {}, deferring the link", email);
            return Optional.empty();
        }
        log.info("Created new account {} from Google sign-in", created.getId());
        return Optional.of(created);
    }
}
