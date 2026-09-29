package nl.hackyourfuture.project.matchingservice;

import nl.hackyourfuture.project.backend.shared.web.TokenSubject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A test-only route that answers what a controller reads: the {@code TokenSubject}'s id, or
 * "none". Not top-matches, so matching's controller can move in beside it (Track E1).
 */
@RestController
class SubjectController {

    @GetMapping("/test/subject")
    String subject() {
        return TokenSubject.current().map(UUID::toString).orElse("none");
    }
}
