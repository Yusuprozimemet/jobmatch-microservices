package nl.hackyourfuture.project.matchingservice;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The one dynamodb-local container the test suite shares, for the score table (Day 22). A static
 * singleton rather than a {@code @Bean} or {@code @Container} field on purpose: Spring caches
 * test contexts per configuration, so a container a context owns starts once per context, and a
 * second context, which any {@code @MockitoBean} or extra property creates, quietly starts a
 * second container. This one starts once per JVM and is never stopped; Ryuk removes it when the
 * JVM exits.
 *
 * <p>Unlike the harness's, this table is the service's own: its tests run with table creation on,
 * so they check the table and its TTL as the service makes them. The region and the emulator's
 * dummy credentials are system properties, which the SDK's default chains read.
 */
final class DynamoDbContainer {

    private static final String IMAGE = "amazon/dynamodb-local:3.3.1";
    private static final int PORT = 8000;

    private static final GenericContainer<?> CONTAINER;

    static {
        CONTAINER = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forListeningPort());
        CONTAINER.start();
        System.setProperty("aws.region", "eu-west-1");
        System.setProperty("aws.accessKeyId", "dummy");
        System.setProperty("aws.secretAccessKey", "dummy");
    }

    private DynamoDbContainer() {
    }

    /** The emulator's URL on its mapped port. */
    public static String endpoint() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT);
    }
}
