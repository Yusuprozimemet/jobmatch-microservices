package nl.hackyourfuture.project.backend.support;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.util.Map;

/**
 * Moves stored scores into the past, so a test can check that a score older than the retention
 * window is not served. It follows the scores wherever they are kept: Postgres until Day 22, the
 * DynamoDB {@link ScoreTable} since, and the tests that call it did not change.
 */
public final class ScoreStore {

    private ScoreStore() {
    }

    /** Moves every stored score's {@code scored_at}, and the {@code ttl} it expires at, back by {@code age}. */
    public static void ageAll(Duration age) {
        DynamoDbClient dynamo = ScoreTable.client();
        AttributeValue seconds = AttributeValue.builder().n(Long.toString(age.toSeconds())).build();
        // ttl is a reserved word in DynamoDB's expressions, so it goes by a placeholder.
        dynamo.scanPaginator(scan -> scan.tableName(ScoreTable.TABLE)).items()
                .forEach(item -> dynamo.updateItem(update -> update.tableName(ScoreTable.TABLE)
                        .key(Map.of(ScoreTable.PARTITION_KEY, item.get(ScoreTable.PARTITION_KEY),
                                ScoreTable.SORT_KEY, item.get(ScoreTable.SORT_KEY)))
                        .updateExpression("SET scored_at = scored_at - :s, #t = #t - :s")
                        .expressionAttributeNames(Map.of("#t", "ttl"))
                        .expressionAttributeValues(Map.of(":s", seconds))));
    }
}
