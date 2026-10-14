package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The harness's score table (Day 22): keyed as the spec's table, without TTL, and emptied by the
 * reset every test starts from. Without the reset, tests sharing a skill set and a posting would
 * find each other's scores.
 */
class ScoreTableTest extends IntegrationTest {

    @Test
    void isKeyedBySkillsHashThenPostingAndScorer() {
        assertThat(ScoreTable.client().describeTable(r -> r.tableName(ScoreTable.TABLE)).table().keySchema())
                .extracting(KeySchemaElement::attributeName, KeySchemaElement::keyType)
                .containsExactly(tuple("skills_hash", KeyType.HASH), tuple("posting_scorer", KeyType.RANGE));
    }

    @Test
    void hasNoTimeToLive() {
        assertThat(ScoreTable.client().describeTimeToLive(r -> r.tableName(ScoreTable.TABLE))
                .timeToLiveDescription().timeToLiveStatus())
                .isEqualTo(TimeToLiveStatus.DISABLED);
    }

    @Test
    void theResetEmptiesIt() {
        for (int posting = 1; posting <= 3; posting++) {
            String sortKey = posting + "#v1";
            ScoreTable.client().putItem(r -> r.tableName(ScoreTable.TABLE).item(Map.of(
                    "skills_hash", AttributeValue.fromS("hash"),
                    "posting_scorer", AttributeValue.fromS(sortKey))));
        }
        assertThat(itemCount()).isEqualTo(3);

        TestDatabase.reset();

        assertThat(itemCount()).isZero();
    }

    private static int itemCount() {
        return ScoreTable.client().scan(r -> r.tableName(ScoreTable.TABLE)).count();
    }
}
