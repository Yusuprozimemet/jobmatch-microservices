package nl.hackyourfuture.project.backend.identity.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.identity.auth.dto.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication and registration operations")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user", description = "Creates a new user account with credentials and returns the account details.")
    @ApiResponse(responseCode = "201", description = "User registered successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Validation failed for the request body",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
    )
    @ApiResponse(
            responseCode = "409",
            description = "An account with this email address already exists",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
    )
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authenticationService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in a user", description = "Verifies user credentials, creates a session cookie, and returns account details.")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        return authenticationService.login(request, httpRequest);
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Forgot password", description = "Triggers a password reset token generation and logs the link for testing.")
    @ApiResponse(responseCode = "200", description = "Password reset request processed")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authenticationService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password", description = "Resets the user password using a valid token.")
    @ApiResponse(responseCode = "200", description = "Password successfully reset")
    @ApiResponse(responseCode = "400", description = "Invalid or expired token")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authenticationService.resetPassword(request);
    }

    @PatchMapping("/password")
    @Operation(summary = "Update password", description = "Updates the password for the currently logged-in user.")
    @ApiResponse(responseCode = "200", description = "Password successfully updated")
    @ApiResponse(responseCode = "400", description = "Invalid current password or Google-only account")
    public void updatePassword(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody UpdatePasswordRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        // Not logged in.
        if (email == null || "anonymousUser".equals(email) || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        authenticationService.updatePassword(email, request, httpRequest);
    }
}