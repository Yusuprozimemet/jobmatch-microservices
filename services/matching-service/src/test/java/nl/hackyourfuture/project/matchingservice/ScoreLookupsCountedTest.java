package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every posting looked up in the score store is counted once, as a hit or a miss: two identical
 * requests for a shortlist of three are three misses, then three hits (Day 22, criterion 7).
 * Read as differences, because the registry lives as long as the context the test classes share.
 */
class ScoreLookupsCountedTest extends MatchingServiceTest {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void reset() {
        StubUpstream.instance().reset();
        model().reset();
    }

    @Test
    void twoIdenticalRequestsAreNMissesThenNHits() throws IOException, InterruptedException {
        TopMatchesRequest page = new TopMatchesRequest(port, managementPort);
        // The model sees only an id's first 8 characters (MatchScorer.shortId), so they must differ there.
        TopMatchesCall call = TopMatchesCall.forNewUser(port, "lookup-1", "lookup-2", "lookup-3");
        model().willScoreInPromptOrder(70, 60, 50);
        String before = page.prometheus();

        call.send();
        String afterFirst = page.prometheus();
        call.send();
        String afterSecond = page.prometheus();

        assertThat(lookups(afterFirst, "miss") - lookups(before, "miss")).isEqualTo(3);
        assertThat(lookups(afterFirst, "hit") - lookups(before, "hit")).isEqualTo(0);
        assertThat(lookups(afterSecond, "miss") - lookups(afterFirst, "miss")).isEqualTo(0);
        assertThat(lookups(afterSecond, "hit") - lookups(afterFirst, "hit")).isEqualTo(3);
        assertThat(model().callCount()).isEqualTo(1);
    }

    // The value is the last token of the series' line: jobmatch_scores_lookups_total{result="hit"} 3.0
    private static double lookups(String metrics, String result) {
        return metrics.lines()
                .filter(line -> line.startsWith("jobmatch_scores_lookups_total{") && line.contains("result=\"" + result + "\""))
                .findFirst()
                .map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .orElseThrow(() -> new AssertionError("no jobmatch_scores_lookups_total{result=\"" + result + "\"} in " + metrics));
    }
}
