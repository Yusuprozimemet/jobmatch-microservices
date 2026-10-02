package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A top-matches request stores one item per scored posting, keyed and attributed as Day 22's spec
 * lays out the table, and expiring a day after it was scored (criterion 1). Reading them back is
 * {@code MatchScoreCacheIT}'s, in the harness.
 */
class ScoresStoredTest extends MatchingServiceTest {

    private static final String TABLE = "job_match_scores";

    @LocalServerPort
    private int port;

    @Autowired
    private DynamoDbClient dynamo;

    @BeforeEach
    void reset() {
        StubUpstream.instance().reset();
        model().reset();
    }

    @Test
    void eachScoredPostingIsOneItemThatExpiresADayLater() throws Exception {
        TopMatchesCall call = TopMatchesCall.forNewUser(port, "stored-1", "stored-2");
        model().willScoreInPromptOrder(81, 64);
        long before = Instant.now().getEpochSecond();

        call.send();

        List<Map<String, AttributeValue>> items = dynamo.scan(scan -> scan.tableName(TABLE)).items().stream()
                .filter(item -> item.get("posting_scorer").s().startsWith("stored-"))
                .toList();
        assertThat(items).hasSize(2);
        assertThat(items).extracting(item -> item.get("score").n()).containsExactlyInAnyOrder("81", "64");
        assertThat(items.stream().map(item -> item.get("skills_hash").s()).distinct()).hasSize(1);
        for (Map<String, AttributeValue> item : items) {
            String postingId = item.get("posting_scorer").s().split("#", 2)[0];
            long scoredAt = Long.parseLong(item.get("scored_at").n());
            assertThat(item.get("posting_scorer").s()).matches(postingId + "#.+");
            assertThat(item.get("reason").s()).isEqualTo("stubbed reason for " + postingId);
            assertThat(scoredAt).isBetween(before, Instant.now().getEpochSecond());
            assertThat(Long.parseLong(item.get("ttl").n())).isEqualTo(scoredAt + 86_400);
        }
    }

    /**
     * Day 27: user.deleted has nothing to erase in this table because no attribute
     * names a user.
     */
    @Test
    void anItemHoldsExactlyTheScoreAttributesAndNoUser() throws Exception {
        TopMatchesCall call = TopMatchesCall.forNewUser(port, "attrs-1", "attrs-2");
        model().willScoreInPromptOrder(70, 60);

        call.send();

        List<Map<String, AttributeValue>> items = dynamo.scan(scan -> scan.tableName(TABLE)).items().stream()
                .filter(item -> item.get("posting_scorer").s().startsWith("attrs-"))
                .toList();
        assertThat(items).hasSize(2);
        for (Map<String, AttributeValue> item : items) {
            assertThat(item.keySet()).containsExactlyInAnyOrder("skills_hash", "posting_scorer", "score",
                    "scored_at", "ttl", "reason");
        }
    }
}
