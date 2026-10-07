package nl.hackyourfuture.project.matchingservice;

import nl.hackyourfuture.project.backend.matching.JobMatchScoreRepository;
import nl.hackyourfuture.project.backend.matching.MatchScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    @Autowired
    private JobMatchScoreRepository scores;

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

    /**
     * Day 42 (H28.7): DynamoDB rejects a whole batch that holds an attribute with a null value, so the
     * repository leaves the reason out; 26 scores span two batches.
     */
    @Test
    void aNullReasonDoesNotDropTheBatch() {
        LinkedHashMap<String, MatchScorer.Score> map = new LinkedHashMap<>();
        for (int i = 1; i <= 26; i++) {
            String id = String.format("null-reason-%02d", i);
            if (i == 13) {
                map.put(id, new MatchScorer.Score(50, null));
            } else {
                map.put(id, new MatchScorer.Score(50, "reason " + id));
            }
        }

        scores.saveScores("null-reason-hash", "v-test", map);

        List<Map<String, AttributeValue>> items = dynamo.scan(scan -> scan.tableName(TABLE)).items().stream()
                .filter(item -> item.get("skills_hash").s().equals("null-reason-hash"))
                .toList();
        assertThat(items).hasSize(26);
        for (Map<String, AttributeValue> item : items) {
            String postingScorer = item.get("posting_scorer").s();
            if (postingScorer.startsWith("null-reason-13#")) {
                assertThat(item).doesNotContainKey("reason");
            } else {
                String postingId = postingScorer.split("#", 2)[0];
                assertThat(item.get("reason").s()).isEqualTo("reason " + postingId);
            }
        }
    }
}
