package nl.hackyourfuture.project.backend.identity.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.identity.profile.dto.ProfileResponse;
import nl.hackyourfuture.project.backend.identity.profile.dto.UpdateProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "The job preferences the logged-in user is matched on")
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    @Operation(summary = "Get the logged-in user's job preferences",
            description = "Self-service only: the account is taken from the session, so a caller "
                    + "cannot read anyone else's. An account that has never saved gets every "
                    + "preference null and an empty skills list, not a 404.")
    @ApiResponse(responseCode = "200", description = "The user's job preferences")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal Object principal) {
        return resolveEmail(principal)
                .map(email -> ResponseEntity.ok(profileService.getProfile(email)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PutMapping
    @Operation(summary = "Save the logged-in user's job preferences",
            description = "Self-service only: the account is taken from the session and the body "
                    + "carries no user id. The whole profile is replaced, so a field left out is "
                    + "cleared - the only way a user can empty something they filled in before. "
                    + "The first save creates the profile; there is no separate POST.")
    @ApiResponse(responseCode = "200", description = "The preferences as stored")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(
            responseCode = "400",
            description = "The request body is invalid",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
    )
    public ResponseEntity<ProfileResponse> saveProfile(
            @AuthenticationPrincipal Object principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return resolveEmail(principal)
                .map(email -> ResponseEntity.ok(profileService.saveProfile(email, request)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // Logged-in user's email, or empty if nobody is logged in.
    private static Optional<String> resolveEmail(Object principal) {
        // Not logged in.
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
}
