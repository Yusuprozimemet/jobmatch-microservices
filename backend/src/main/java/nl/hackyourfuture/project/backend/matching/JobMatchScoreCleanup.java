package nl.hackyourfuture.project.backend.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Deletes expired scores to free up space. Just housekeeping - reads already filter out
// expired scores on their own, so it's fine if this runs late or not at all.
// Runs hourly so rows don't sit around for up to a day before being cleaned up.
@Slf4j
@Component
@RequiredArgsConstructor
public class JobMatchScoreCleanup {

    private final JobMatchScoreRepository scoreRepository;

    @Scheduled(cron = "${app.llm.score-purge-cron:0 0 * * * *}")
    public void purgeExpiredScores() {
        try {
            int removed = scoreRepository.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired job match scores", removed);
            }
        } catch (Exception e) {
            // A failed purge just wastes disk space, nothing breaks. Log the full error so
            // the cause is visible if it keeps happening.
            log.warn("Job match score purge failed, will retry on the next schedule", e);
        }
    }
}
