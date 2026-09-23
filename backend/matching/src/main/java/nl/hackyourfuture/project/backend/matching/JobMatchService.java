package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.shared.jobs.PostingShortlist;
import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.matching.dto.JobMatchResponse;
import nl.hackyourfuture.project.backend.shared.identity.ProfileDirectory;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import nl.hackyourfuture.project.backend.shared.identity.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Ranks open postings against the user's profile in two steps:
// 1. SQL narrows the mart down to a shortlist by city and exact skill overlap.
// 2. The model re-ranks that shortlist, catching synonyms and seniority SQL can't.
// Scores are saved to job_match_scores, so a restart doesn't lose them.
@Service
@RequiredArgsConstructor
public class JobMatchService {

    // The same floor the profile form enforces when saving, defined once in shared.
    static final int MINIMUM_PROFILE_SKILLS = ProfileSnapshot.MINIMUM_SKILLS;
    static final int SHORTLIST_SIZE = 40;
    static final int RESULT_LIMIT = 25;
    // Where "strong match" starts, per the label's documented contract.
    static final int STRONG_MATCH_PERCENT = 60;
    // Floor for the matchPercent denominator, so a job listing one or two skills can't
    // read as a 100% match off a single overlap.
    static final int MIN_PERCENT_DENOMINATOR = 5;

    private final PostingShortlist postingShortlist;
    private final JobMatchScoreRepository jobMatchScoreRepository;
    private final ProfileDirectory profileDirectory;
    private final UserDirectory userDirectory;
    private final MatchScorer matchScorer;

    public List<JobMatchResponse> getTopMatches(String email) {
        ProfileSnapshot profile = loadProfile(email);
        List<String> skills = canonicalise(profile.skills());

        if (skills.size() < MINIMUM_PROFILE_SKILLS) {
            // Validation keeps new saves above the floor, but older profiles can be short.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Add at least " + MINIMUM_PROFILE_SKILLS + " skills to your profile to see matches. You have "
                            + skills.size() + ".");
        }

        List<ShortlistedPosting> shortlist =
                postingShortlist.shortlist(profile.preferredCity(), skills, SHORTLIST_SIZE);
        if (shortlist.isEmpty()) {
            return List.of();
        }

        Map<String, MatchScorer.Score> scores = resolveScores(skills, shortlist);

        return shortlist.stream()
                .map(row -> toResponse(row, skills.size(), scores.get(row.postingId())))
                .sorted(Comparator.comparingInt(JobMatchResponse::score).reversed()
                        .thenComparing(JobMatchResponse::matchedCount, Comparator.reverseOrder()))
                .limit(RESULT_LIMIT)
                .toList();
    }

    // Reuse stored scores, only ask the model for postings that don't have one yet.
    private Map<String, MatchScorer.Score> resolveScores(List<String> skills,
                                                         List<ShortlistedPosting> shortlist) {
        String skillsHash = skillsHash(skills);
        String scorerVersion = matchScorer.version();
        List<String> postingIds = shortlist.stream().map(ShortlistedPosting::postingId).toList();

        Map<String, MatchScorer.Score> scores =
                new HashMap<>(jobMatchScoreRepository.findScores(skillsHash, scorerVersion, postingIds));

        List<ShortlistedPosting> unscored = shortlist.stream()
                .filter(row -> !scores.containsKey(row.postingId()))
                .toList();
        if (unscored.isEmpty()) {
            return scores;
        }

        Map<String, MatchScorer.Score> fresh = matchScorer.score(skills, unscored);
        if (!fresh.isEmpty()) {
            jobMatchScoreRepository.saveScores(skillsHash, scorerVersion, fresh);
        }
        scores.putAll(fresh);
        return scores;
    }

    // No account and no profile are the same answer to the caller: there is nothing to rank
    // against. Asking identity for both keeps the users and user_profiles tables out of here.
    private ProfileSnapshot loadProfile(String email) {
        return userDirectory.findUserIdByEmail(email)
                .flatMap(profileDirectory::forUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Fill in your profile to see matching jobs."));
    }

    // Just lowercase + trim. Doesn't collapse hyphens like ProfileService does - the mart's
    // spelling is always hyphenated, so that would break matches, not fix them.
    private static List<String> canonicalise(List<String> skills) {
        if (skills == null) {
            return List.of();
        }
        return skills.stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .map(skill -> skill.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    // A fixed-size id for a skill set, used to look up stored scores. Sorted first so the
    // order skills were picked in doesn't matter. City isn't included - the model never sees it.
    private static String skillsHash(List<String> skills) {
        String canonical = String.join("\n", skills.stream().sorted().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JobMatchResponse toResponse(ShortlistedPosting row, int ofSkills,
                                               MatchScorer.Score score) {
        double coverage = jobCoverage(row);
        int percent = Math.round((float) coverage * 100);
        return new JobMatchResponse(
                row.postingId(),
                row.title(),
                row.company(),
                row.location(),
                row.category(),
                row.postedDate(),
                row.matchedSkills(),
                row.matchedCount(),
                ofSkills,
                row.jobSkillCount(),
                coverage,
                percent,
                percent >= STRONG_MATCH_PERCENT ? "strong match" : null,
                score != null ? score.value() : percent,
                score != null ? score.reason() : null,
                score != null
        );
    }

    // How much of what the job asks for the candidate already has. Used as matchScore/matchPercent,
    // and as a stand-in for score when the model hasn't ranked a row yet.
    // Divides by the job's skill count, not the candidate's - otherwise a long profile could
    // never hit 100%, even for a perfect match.
    private static double jobCoverage(ShortlistedPosting row) {
        // No clamping needed: matchedSkills comes from the job's own skills, so it can
        // never be bigger than jobSkillCount.
        return (double) row.matchedCount() / Math.max(row.jobSkillCount(), MIN_PERCENT_DENOMINATOR);
    }
}
