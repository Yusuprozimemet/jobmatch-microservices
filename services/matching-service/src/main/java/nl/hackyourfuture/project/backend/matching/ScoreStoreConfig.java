package nl.hackyourfuture.project.backend.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

import java.net.URI;
import java.time.Duration;

/**
 * The score store's client (Day 22). On AWS the endpoint is blank and the table is Terraform's
 * (Day 32); in compose and the service's tests the endpoint is dynamodb-local, and with
 * {@code app.scores.create-table} on the service creates the table itself.
 *
 * <p>The call timeout covers the whole call, retries included, so a store that is down or hung
 * costs a request at most that much per call, never the gateway's 30 s.
 */
@Configuration(proxyBeanMethods = false)
class ScoreStoreConfig {

    static final String PARTITION_KEY = "skills_hash";
    static final String SORT_KEY = "posting_scorer";
    static final String TTL_ATTRIBUTE = "ttl";

    @Bean(destroyMethod = "close")
    DynamoDbClient scoresDynamoDbClient(
            @Value("${app.scores.endpoint}") String endpoint,
            @Value("${app.scores.region}") String region,
            @Value("${app.scores.api-call-timeout}") Duration timeout) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(timeout));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Creates the table if it is absent, then turns on TTL on {@code ttl}, before the service
     * serves. A failure stops startup: the flag is for local runs, where that is the clearer
     * answer. The harness creates its own table, without TTL, and runs with the flag off.
     */
    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.scores.create-table", havingValue = "true")
    static class ScoreTableCreator implements InitializingBean {

        private final DynamoDbClient dynamo;
        private final String table;

        ScoreTableCreator(DynamoDbClient dynamo, @Value("${app.scores.table}") String table) {
            this.dynamo = dynamo;
            this.table = table;
        }

        @Override
        public void afterPropertiesSet() {
            try {
                dynamo.describeTable(describe -> describe.tableName(table));
            } catch (ResourceNotFoundException absent) {
                create();
            }
            TimeToLiveStatus ttl = dynamo.describeTimeToLive(describe -> describe.tableName(table))
                    .timeToLiveDescription().timeToLiveStatus();
            // DynamoDB refuses to enable TTL twice, so only when it is off.
            if (ttl != TimeToLiveStatus.ENABLED && ttl != TimeToLiveStatus.ENABLING) {
                dynamo.updateTimeToLive(update -> update.tableName(table)
                        .timeToLiveSpecification(spec -> spec.enabled(true).attributeName(TTL_ATTRIBUTE)));
            }
        }

        private void create() {
            try {
                dynamo.createTable(create -> create.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .keySchema(key(PARTITION_KEY, KeyType.HASH), key(SORT_KEY, KeyType.RANGE))
                        .attributeDefinitions(string(PARTITION_KEY), string(SORT_KEY)));
                log.info("Created the score table {}", table);
            } catch (ResourceInUseException createdMeanwhile) {
                // Another instance created it between the describe and the create.
            }
            dynamo.waiter().waitUntilTableExists(describe -> describe.tableName(table));
        }

        private static KeySchemaElement key(String name, KeyType type) {
            return KeySchemaElement.builder().attributeName(name).keyType(type).build();
        }

        private static AttributeDefinition string(String name) {
            return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
        }
    }
}
