package nl.hackyourfuture.project.backend.postings;

import nl.hackyourfuture.project.backend.shared.jobs.PostingShortlist;
import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.ShortlistFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The match shortlist's order, pinned in process before Day 18 puts it behind
 * {@code POST /internal/postings/shortlist}, which is held to the same {@link ShortlistFixture}.
 * Day 09's Notes found the order pinned by 1 test of 22.
 */
class ShortlistOrderIT extends IntegrationTest {

    @Autowired @Qualifier("jobsDirectory")
    private PostingShortlist shortlist; // The in-process implementation, not the @Primary HTTP client.

    @Test
    void ranksByMatchesThenDateThenIdOnePerRepostInTheCityUpToTheLimit() {
        ShortlistFixture.create(jdbc());

        List<String> result = shortlist.shortlist(ShortlistFixture.CITY, ShortlistFixture.SKILLS, ShortlistFixture.LIMIT)
                .stream()
                .map(ShortlistedPosting::postingId)
                .toList();

        assertThat(result).containsExactlyElementsOf(ShortlistFixture.EXPECTED);
    }
}
