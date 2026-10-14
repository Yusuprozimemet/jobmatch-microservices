package nl.hackyourfuture.project.backend.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;

/**
 * matching-service's score table (Day 22): a dynamodb-local container, one for the whole run, on the
 * network matching-service's container joins; Ryuk removes it. The harness creates the table, so the
 * service is started with table creation off, and {@link TestDatabase#reset()} empties it.
 *
 * <p>No TTL on the table, on purpose: the emulator sweeps expired items within seconds, which would
 * hide a read that serves an expired score. The read filter is what a test here checks.
 */
public final class ScoreTable {

    public static final String IMAGE = "amazon/dynamodb-local:3.3.1";
    public static final String TABLE = "job_match_scores";
    public static final String PARTITION_KEY = "skills_hash";
    public static final String SORT_KEY = "posting_scorer";
    /** Where matching-service reaches the table: the container's alias on the shared network. */
    public static final String NETWORK_ENDPOINT = "http://dynamodb:8000";

    private static final int PORT = 8000;
    private static final Network NETWORK = Network.newNetwork();

    private static volatile DynamoDbClient client;

    private ScoreTable() {
    }

    /** The network the table's container is on, for matching-service's to join. */
    public static Network network() {
        return NETWORK;
    }

    /** A client on the table's mapped port, with the emulator's dummy credentials. */
    public static DynamoDbClient client() {
        ensureStarted();
        return client;
    }

    /** Deletes every item. A scan is enough: a test stores a few dozen scores at most. */
    public static void empty() {
        DynamoDbClient dynamo = client();
        dynamo.scanPaginator(scan -> scan.tableName(TABLE).projectionExpression(PARTITION_KEY + ", " + SORT_KEY))
                .items()
                .forEach(key -> dynamo.deleteItem(delete -> delete.tableName(TABLE).key(key)));
    }

    private static synchronized void ensureStarted() {
        if (client != null) {
            return;
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(NETWORK)
                .withNetworkAliases("dynamodb")
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forListeningPort());
        container.start();
        DynamoDbClient dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT)))
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")))
                .build();
        dynamo.createTable(table -> table.tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(key(PARTITION_KEY, KeyType.HASH), key(SORT_KEY, KeyType.RANGE))
                .attributeDefinitions(string(PARTITION_KEY), string(SORT_KEY)));
        dynamo.waiter().waitUntilTableExists(table -> table.tableName(TABLE));
        client = dynamo;
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeDefinition string(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }
}
