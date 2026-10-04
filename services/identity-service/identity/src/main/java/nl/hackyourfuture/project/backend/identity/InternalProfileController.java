package nl.hackyourfuture.project.backend.identity;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A user's profile snapshot, for a service that ranks for a user and has no {@code user_profiles}
 * table (Day 41). 404 for a user with no profile. Behind the {@code /internal/**} chain: service
 * tokens only.
 *
 * <p>Takes {@link IdentityDirectory} itself, not the {@code ProfileDirectory} interface, because
 * from Day 41 Track B the interface's {@code @Primary} bean is an HTTP client that calls this
 * controller.
 */
@RestController
@RequiredArgsConstructor
class InternalProfileController {

    private final IdentityDirectory identityDirectory;

    @GetMapping("/internal/profiles/{userId}")
    ResponseEntity<ProfileSnapshot> profile(@PathVariable UUID userId) {
        return identityDirectory.forUser(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
