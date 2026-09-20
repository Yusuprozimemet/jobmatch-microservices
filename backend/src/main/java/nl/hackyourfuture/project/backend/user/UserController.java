package nl.hackyourfuture.project.backend.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.user.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Operations on user accounts")
public class UserController {

    private final UserService userService;

    // Lets the frontend check who is logged in.
    @GetMapping("/me")
    @Operation(summary = "Get current logged-in user", description = "Returns the user details associated with the current session.")
    @ApiResponse(responseCode = "200", description = "The current authenticated user")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Object principal) {
        return resolveEmail(principal)
                .map(email -> ResponseEntity.ok(userService.getUserByEmail(email)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/me/accept-terms")
    @Operation(summary = "Agree to the terms and privacy policy",
            description = "Records the logged-in user's agreement. Registration already does this, "
                    + "so this is for Google sign-ups, which never saw the checkbox. "
                    + "Calling it again keeps the original timestamp.")
    @ApiResponse(responseCode = "200", description = "The user, with the agreement stamped")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    public ResponseEntity<UserResponse> acceptTerms(@AuthenticationPrincipal Object principal) {
        return resolveEmail(principal)
                .map(email -> ResponseEntity.ok(userService.acceptTerms(email)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PutMapping("/me")
    @Operation(summary = "Update the current logged-in user",
            description = "Self-service only: the account is taken from the session, so a caller "
                    + "cannot edit anyone else. The email in the body is ignored - the address is "
                    + "the login identity and changing it needs a verification flow we do not have yet.")
    @ApiResponse(responseCode = "200", description = "The updated user")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(
            responseCode = "400",
            description = "The request body is invalid",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
    )
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal Object principal,
            @Valid @RequestBody UserRequest request) {
        return resolveEmail(principal)
                .map(email -> ResponseEntity.ok(userService.updateCurrentUser(email, request)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @DeleteMapping("/me")
    @Operation(summary = "Delete the current logged-in user",
            description = "Self-service only: the account is taken from the session, so a caller "
                    + "cannot delete anyone else. Also ends the session and clears JSESSIONID.")
    @ApiResponse(responseCode = "204", description = "The account was deleted")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    public ResponseEntity<Void> deleteCurrentUser(
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        Optional<String> email = resolveEmail(principal);
        if (email.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        userService.deleteUserByEmail(email.get());

        // End the session too, or /me would answer 404 instead of 401.
        endSession(request, response);

        return ResponseEntity.noContent().build();
    }

    // The caller's email, or empty when nobody is logged in.
    private static Optional<String> resolveEmail(Object principal) {
        // Not logged in, or an anonymous placeholder.
        if (principal instanceof String principalEmail) {
            if ("anonymousUser".equals(principalEmail) || principalEmail.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(principalEmail);
        }
        if (principal instanceof UserDetails userDetails) {
            return Optional.of(userDetails.getUsername());
        }
        return Optional.empty();
    }

    // Same as the logout handler in SecurityConfig.
    private static void endSession(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
    }
}
