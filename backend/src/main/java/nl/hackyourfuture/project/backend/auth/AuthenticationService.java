package nl.hackyourfuture.project.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.auth.dto.*;
import nl.hackyourfuture.project.backend.user.User;
import nl.hackyourfuture.project.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String baseUrl;

    // Creates the account, stores the hashed password, and records the terms agreement.
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        // Checking if a user with this email already exists
        if (userRepository.getUserByEmail(normalizedEmail).isPresent()) {
            throw new DuplicateKeyException("Email already registered");
        }
        // UUID for the user
        UUID userId = UUID.randomUUID();

        // Creating the user object matching the base users table
        User newUser = User.builder()
                .id(userId)
                .email(normalizedEmail)
                .name(request.name())
                .build();


        try {
            userRepository.createUser(newUser);
        } catch (DuplicateKeyException e) {
            // Catches duplicate emails if two users register at the exact same time
            log.info("Concurrent registration race condition caught for email: {}", normalizedEmail);
            throw new DuplicateKeyException("Email already registered", e);
        }

        // Hashing password
        String hashedPassword = passwordEncoder.encode(request.password());
        // save the hashed password in the user_credentials table
        userRepository.createUserCredentials(userId, hashedPassword);

        // Same transaction as the account: no personal data stored without the agreement.
        userRepository.acceptTerms(userId);

        return new RegisterResponse(
                userId,
                request.email(),
                request.name(),
                "User registered successfully"
        );
    }

    // Checks the password and starts the session (JSESSIONID).
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        // Look up user credentials by email or fail with a generic security error
        var credentials = userRepository.findCredentialsByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // A Google-only account has no user_credentials row, so the lookup above already
        // failed; the null check only guards a row written without a hash.
        if (credentials.passwordHash() == null
                || !passwordEncoder.matches(request.password(), credentials.passwordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        establishSession(credentials.email(), httpRequest);
        completePendingGoogleLink(credentials, httpRequest);

        // Null timestamp means they never agreed; the frontend shows the terms screen.
        return new LoginResponse(
                credentials.email(),
                credentials.name(),
                credentials.termsAcceptedAt()
        );
    }

    // Creates a reset token and emails the link.
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        var userOpt = userRepository.getUserByEmail(normalizedEmail);

        // for security (Always 200): Don't reveal if the email exists or not.
        if (userOpt.isPresent()) {
            var user = userOpt.get();

            // Google-only accounts have no credentials record, so a reset link would dead-end
            // at resetPassword(). Skip it silently to keep the response indistinguishable.
            if (userRepository.findPasswordHashByUserId(user.getId()).isEmpty()) {
                log.info("Skipping password reset email for Google-only user ID: {}", user.getId());
                return;
            }

            // Clear any old tokens for this user
            userRepository.deletePasswordResetTokensByUserId(user.getId());

            UUID tokenId = UUID.randomUUID();
            String token = UUID.randomUUID() + "-" + UUID.randomUUID();
            java.time.OffsetDateTime expiryDate = java.time.OffsetDateTime.now().plusMinutes(15);

            userRepository.savePasswordResetToken(tokenId, user.getId(), token, expiryDate);

            // Send the real email via Brevo
            String resetUrl = baseUrl + "/reset-password?token=" + token;

            // Ensure the token is fully committed to the database before the email goes out
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendPasswordResetEmail(user.getEmail(), resetUrl);
                }
            });
        }

    }

    // Checks the reset token, then sets the new password.
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        UUID userId = userRepository.findUserIdByValidResetToken(request.token())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Invalid or expired password reset token"));

        // Reject Google-only accounts since they don't have a local password credentials record
        userRepository.findPasswordHashByUserId(userId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Google-only accounts cannot reset passwords"));

        String hashedPassword = passwordEncoder.encode(request.newPassword());
        userRepository.updatePasswordHash(userId, hashedPassword);
        userRepository.deletePasswordResetTokensByUserId(userId);

        log.info("Password successfully reset for user ID: {}", userId);
    }

    // Changes the password after checking the current one.
    @Transactional
    public void updatePassword(String email, UpdatePasswordRequest request, HttpServletRequest httpRequest) {
        // Look up the user ID using the provided email
        UUID userId = userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))
                .getId();

        // Check if the user has a credentials record (fails cleanly for Google-only accounts)
        String currentPasswordHash = userRepository.findPasswordHashByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Google-only accounts cannot update passwords via this endpoint"));

        // Verify the current password matches the stored hash
        if (!passwordEncoder.matches(request.currentPassword(), currentPasswordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        // Hash the new password
        String newPasswordHash = passwordEncoder.encode(request.newPassword());

        userRepository.updatePasswordHash(userId, newPasswordHash);
        // Refresh/re-establish the session to invalidate any old/stolen session contexts
        establishSession(email, httpRequest);

        log.info("Password successfully updated for user email: {}", email);
    }

    // A Google sign-in that found this email waits in the session until a password login
    // proves the account. This is that proof, so the identity can be attached now.
    private void completePendingGoogleLink(UserRepository.UserCredentialsRecord credentials,
                                           HttpServletRequest httpRequest) {
        PendingGoogleLink.claim(httpRequest.getSession(false), credentials.email())
                .ifPresent(providerId -> {
                    boolean linked = userRepository.linkProvider(
                            credentials.id(), OAuth2LoginSuccessHandler.PROVIDER_GOOGLE, providerId);
                    log.info("Google sign-in {} for account {}",
                            linked ? "linked" : "not linked, another identity is already attached",
                            credentials.id());
                });
    }

    // Stores the security context on a fresh session (JSESSIONID). Shared with Google sign-in.
    public void establishSession(String email, HttpServletRequest httpRequest) {
        // Create Spring Security authentication token
        var authToken = new UsernamePasswordAuthenticationToken(
                email,
                null,
                Collections.emptyList() // Placeholder for roles/authorities
        );

        // Set the authentication in the Security Context
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authToken);
        SecurityContextHolder.setContext(securityContext);

        // Create an HTTP session and store the security context so the user stays logged in
        var session = httpRequest.getSession(true);
        httpRequest.changeSessionId(); //  Change session ID to prevent session fixation
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
    }
}
