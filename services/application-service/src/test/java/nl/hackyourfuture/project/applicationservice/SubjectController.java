package nl.hackyourfuture.project.applicationservice;

import nl.hackyourfuture.project.backend.shared.web.CurrentUserId;
import nl.hackyourfuture.project.backend.shared.web.TokenSubject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Test-only routes that answer what a controller reads: the {@code TokenSubject}'s id, or "none";
 * the {@code @CurrentUserId} parameter, or 404 "User not found" as the saved-jobs controller answers
 * it; and the annotation on a parameter of the wrong type. Not under {@code /api/saved-jobs}, so the
 * saved-jobs controllers can move in beside them (Track E1).
 */
@RestController
class SubjectController {

    @GetMapping("/test/subject")
    String subject() {
        return TokenSubject.current().map(UUID::toString).orElse("none");
    }

    @GetMapping("/test/current-user")
    String currentUser(@CurrentUserId Optional<UUID> userId) {
        return userId.map(UUID::toString)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @GetMapping("/test/wrong-type")
    String wrongType(@CurrentUserId UUID userId) {
        return userId.toString();
    }
}
