package nl.hackyourfuture.project.backend.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nl.hackyourfuture.project.backend.matching.ScoreStoreConfig.PARTITION_KEY;
import static nl.hackyourfuture.project.backend.matching.ScoreStoreConfig.SORT_KEY;
import static nl.hackyourfuture.project.backend.matching.ScoreStoreConfig.TTL_ATTRIBUTE;

// Stores what the model scored for a (skill set, posting) pair, so it's only asked once.
// One item per posting, so a mostly-known shortlist only costs the new postings.
// A stored score expires after the retention window; every read checks freshness.
// The store is never worth failing a ranking for: a read that fails is all misses, and a
// write that fails is dropped. The client's call timeout bounds what a hung store costs.
@Slf4j
@Repository
public class JobMatchScoreRepository {

    // Never allow less than a day, or scores would expire as fast as they're written.
    private static final int MINIMUM_RETENTION_DAYS = 1;
    private static final long SECONDS_PER_DAY = 86_400;
    // BatchWriteItem takes at most 25 items a call.
    private static final int WRITE_BATCH_SIZE = 25;

    private static final String SCORE = "score";
    private static final String REASON = "reason";
    private static final String SCORED_AT = "scored_at";

    private final DynamoDbClient dynamo;
    private final String table;
    private final int retentionDays;

    public JobMatchScoreRepository(
            DynamoDbClient dynamo,
            @Value("${app.scores.table}") String table,
            @Value("${app.llm.score-retention-days:1}") int retentionDays
    ) {
        this.dynamo = dynamo;
        this.table = table;
        if (retentionDays < MINIMUM_RETENTION_DAYS) {
            log.warn("app.llm.score-retention-days is {}, which would expire verdicts as fast as they "
                    + "are written; using {} instead.", retentionDays, MINIMUM_RETENTION_DAYS);
        }
        this.retentionDays = Math.max(retentionDays, MINIMUM_RETENTION_DAYS);
    }

    // Returns a stored score for each posting that still has a fresh one. Missing is normal,
    // not an error - the caller just scores those postings again.
    public Map<String, MatchScorer.Score> findScores(String skillsHash, String scorerVersion,
                                                     Collection<String> postingIds) {
        if (postingIds.isEmpty()) {
            return Map.of();
        }
        // One BatchGetItem: the shortlist is at most 40 postings, and a call takes 100 keys.
        Map<String, String> postingBySortKey = postingIds.stream().distinct()
                .collect(Collectors.toMap(postingId -> sortKey(postingId, scorerVersion), Function.identity()));
        List<Map<String, AttributeValue>> keys = postingBySortKey.keySet().stream()
                .map(sortKey -> Map.of(PARTITION_KEY, string(skillsHash), SORT_KEY, string(sortKey)))
                .toList();

        List<Map<String, AttributeValue>> items;
        try {
            // Keys left unprocessed are misses: rescoring them is cheaper than a retry's wait.
            items = dynamo.batchGetItem(get -> get.requestItems(Map.of(table, KeysAndAttributes.builder().keys(keys).build())))
                    .responses().getOrDefault(table, List.of());
        } catch (SdkException e) {
            log.warn("Could not read job match scores, all {} will be rescored", postingIds.size(), e);
            return Map.of();
        }

        // DynamoDB deletes expired items only within days, so the read decides what is served.
        long now = Instant.now().getEpochSecond();
        Map<String, MatchScorer.Score> scores = new HashMap<>();
        for (Map<String, AttributeValue> item : items) {
            if (Long.parseLong(item.get(TTL_ATTRIBUTE).n()) <= now) {
                continue;
            }
            AttributeValue reason = item.get(REASON);
            scores.put(postingBySortKey.get(item.get(SORT_KEY).s()),
                    new MatchScorer.Score(Integer.parseInt(item.get(SCORE).n()), reason == null ? null : reason.s()));
        }
        return scores;
    }

    // Saves freshly scored postings. Never throws - if the save fails, the user still gets
    // their ranking, and that posting just gets scored again next time.
    // A put overwrites, so a rescore replaces the old item instead of leaving it behind.
    public void saveScores(String skillsHash, String scorerVersion,
                           Map<String, MatchScorer.Score> scores) {
        if (scores.isEmpty()) {
            return;
        }
        long scoredAt = Instant.now().getEpochSecond();
        long ttl = scoredAt + retentionDays * SECONDS_PER_DAY;
        List<WriteRequest> puts = new ArrayList<>();
        scores.forEach((postingId, score) -> {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(PARTITION_KEY, string(skillsHash));
            item.put(SORT_KEY, string(sortKey(postingId, scorerVersion)));
            item.put(SCORE, number(score.value()));
            item.put(SCORED_AT, number(scoredAt));
            item.put(TTL_ATTRIBUTE, number(ttl));
            // The model may give no reason, and an attribute can't hold a null.
            if (score.reason() != null) {
                item.put(REASON, string(score.reason()));
            }
            puts.add(WriteRequest.builder().putRequest(put -> put.item(item)).build());
        });

        try {
            for (int from = 0; from < puts.size(); from += WRITE_BATCH_SIZE) {
                List<WriteRequest> batch = puts.subList(from, Math.min(from + WRITE_BATCH_SIZE, puts.size()));
                BatchWriteItemResponse response = dynamo.batchWriteItem(write -> write.requestItems(Map.of(table, batch)));
                int unprocessed = response.unprocessedItems().getOrDefault(table, List.of()).size();
                if (unprocessed > 0) {
                    log.warn("Could not store {} job match scores, they will be rescored later", unprocessed);
                }
            }
        } catch (SdkException e) {
            // Log the full exception, not just its message, so the real cause is visible later.
            log.warn("Could not store {} job match scores, they will be rescored later", scores.size(), e);
        }
    }

    private static String sortKey(String postingId, String scorerVersion) {
        return postingId + "#" + scorerVersion;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
