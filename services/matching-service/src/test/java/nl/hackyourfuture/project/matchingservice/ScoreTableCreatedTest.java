package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * With table creation on, the service makes the score table as Day 22's spec keys it and turns on
 * TTL, which expires a score a day after it was stored. Criterion 1's table; its items come with
 * the repository.
 */
class ScoreTableCreatedTest extends MatchingServiceTest {

    private static final String TABLE = "job_match_scores";

    @Autowired
    private DynamoDbClient dynamo;

    @Test
    void itIsKeyedBySkillSetThenPostingAndScorer() {
        TableDescription table = dynamo.describeTable(describe -> describe.tableName(TABLE)).table();

        assertThat(table.keySchema())
                .extracting(KeySchemaElement::attributeName, key -> key.keyTypeAsString())
                .containsExactly(tuple("skills_hash", "HASH"), tuple("posting_scorer", "RANGE"));
        assertThat(table.attributeDefinitions())
                .extracting(AttributeDefinition::attributeName, attribute -> attribute.attributeTypeAsString())
                .containsExactlyInAnyOrder(tuple("skills_hash", "S"), tuple("posting_scorer", "S"));
    }

    @Test
    void itExpiresItemsByTtl() {
        TimeToLiveDescription ttl = dynamo.describeTimeToLive(describe -> describe.tableName(TABLE))
                .timeToLiveDescription();

        assertThat(ttl.timeToLiveStatus()).isEqualTo(TimeToLiveStatus.ENABLED);
        assertThat(ttl.attributeName()).isEqualTo("ttl");
    }
}
