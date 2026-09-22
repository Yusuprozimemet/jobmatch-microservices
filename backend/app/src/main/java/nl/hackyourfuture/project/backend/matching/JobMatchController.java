package nl.hackyourfuture.project.backend.matching;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.matching.dto.JobMatchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;


@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Job postings ranked against the logged-in user's profile")
public class JobMatchController {

    private final JobMatchService jobMatchService;

    @GetMapping("/top-matches")
    @Operation(summary = "Jobs ranked against the logged-in user's skills",
            description = "Self-service only: the profile comes from the session user, so a caller "
                    + "cannot rank against anyone else's. Postings are narrowed by preferred city "
                    + "(equality on the resolved city: a remote posting elsewhere does not qualify) "
                    + "and exact skill overlap, one row per title and "
                    + "company so a reposted job is not returned twice, then that shortlist is "
                    + "scored 0-100 by a language model, which is what makes postgres match a job "
                    + "asking for postgresql. If the model is unavailable the skill-overlap order is "
                    + "returned instead, with aiScored false on every row.")
    @ApiResponse(responseCode = "200", description = "Up to 25 matching jobs, best first")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "422", description = "No profile, or fewer than 5 skills on it")
    public ResponseEntity<List<JobMatchResponse>> getTopMatches(
            @AuthenticationPrincipal Object principal
    ) {
        Optional<String> email = resolveEmail(principal);
        if (email.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(jobMatchService.getTopMatches(email.get()));
    }

    private static Optional<String> resolveEmail(Object principal) {
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
