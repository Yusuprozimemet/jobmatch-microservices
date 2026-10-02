package nl.hackyourfuture.project.matchingservice;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * The one LocalStack SQS container the test suite shares, for consumer tests (Day 27). A static
 * singleton rather than a {@code @Bean} or {@code @Container} field on purpose: Spring caches
 * test contexts per configuration, so a container a context owns starts once per context, and a
 * second context, which any {@code @MockitoBean} or extra property creates, quietly starts a
 * second container. This one starts once per JVM and is never stopped; Ryuk removes it when the
 * JVM exits.
 *
 * <p>Each consumer test creates a queue and dead-letter queue of its own, so no test takes another's
 * messages. The client below carries the region and the emulator's dummy credentials.
 */
final class SqsContainer {

    /** Same tag as docker-compose.yml's localstack service. */
    private static final String IMAGE = "localstack/localstack:4.14.0";
    private static final int PORT = 4566;
    static final int MAX_RECEIVES = 5;

    private static final GenericContainer<?> CONTAINER;
    private static final SqsClient CLIENT;
    private static final String ENDPOINT;

    static {
        CONTAINER = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("SERVICES", "sqs")
                .withEnv("AWS_DEFAULT_REGION", "eu-west-1")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(PORT));
        CONTAINER.start();

        ENDPOINT = "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT);
        CLIENT = SqsClient.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy", "dummy")))
                .build();
    }

    private SqsContainer() {
    }

    /** The emulator's URL on its mapped port. */
    public static String endpoint() {
        return ENDPOINT;
    }

    /** A client on the emulator's mapped port, with dummy credentials. */
    public static SqsClient sqs() {
        return CLIENT;
    }

    /** The dead-letter queue name for a given queue. */
    public static String deadLetterQueue(String queue) {
        return queue + "-dlq";
    }

    /**
     * Creates a queue and its dead-letter queue for a consumer's own test, returns the queue URL.
     * 1 s visibility timeout so a failed message reaches the DLQ in about 5 s instead of over 2
     * minutes at SQS's default 30 s.
     */
    public static String createOwnQueue(String queueName) {
        String dlqName = deadLetterQueue(queueName);

        String dlqUrl = CLIENT.createQueue(r -> r.queueName(dlqName)).queueUrl();
        String dlqArn = CLIENT.getQueueAttributes(r -> r
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + MAX_RECEIVES + "\"}";
        Map<QueueAttributeName, String> attrs = new HashMap<>();
        attrs.put(QueueAttributeName.VISIBILITY_TIMEOUT, "1");
        attrs.put(QueueAttributeName.REDRIVE_POLICY, redrivePolicy);
        return CLIENT.createQueue(r -> r
                .queueName(queueName)
                .attributes(attrs))
                .queueUrl();
    }

    /** The URL of a queue by its name, via getQueueUrl. */
    public static String queueUrlOf(String queueName) {
        return CLIENT.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
    }

    /** Approximate count of visible and in-flight messages in a queue. */
    public static int approximateCount(String queueUrl) {
        var attrs = CLIENT.getQueueAttributes(r -> r
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .attributes();
        int visible = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        int notVisible = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        return visible + notVisible;
    }
}
