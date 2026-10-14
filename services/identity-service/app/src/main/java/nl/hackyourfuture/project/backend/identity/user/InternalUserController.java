package nl.hackyourfuture.project.backend.identity.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Whether a user still exists, for a service that trusts a token's {@code sub} and has no
 * {@code users} table (Day 39). Behind the {@code /internal/**} chain: service tokens only.
 * Not cached: an answer cached for N seconds lets a deleted user in for N seconds.
 */
@RestController
@RequiredArgsConstructor
class InternalUserController {

    private final UserService userService;

    @GetMapping("/internal/users/{id}")
    ResponseEntity<Void> exists(@PathVariable UUID id) {
        return userService.exists(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
