package nl.hackyourfuture.project.backend.matching;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.matching.dto.JobMatchResponse;
import nl.hackyourfuture.project.backend.shared.web.TokenSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Job postings ranked against the logged-in user's profile")
public class JobMatchController {

    private final JobMatchService jobMatchService;
    private final UserExistenceClient users;

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
    @ApiResponse(responseCode = "503", description = "Identity could not be reached")
    public ResponseEntity<List<JobMatchResponse>> getTopMatches() {
        // No account is answered like no profile: there is nothing to rank against.
        UUID id = TokenSubject.current().filter(users::exists).orElseThrow(JobMatchService::noProfile);
        return ResponseEntity.ok(jobMatchService.getTopMatches(id));
    }
}
