package nl.hackyourfuture.project.backend.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The event bus (Day 26): a LocalStack container with SNS and SQS, one for the whole run,
 * Ryuk removes it. The bus creates the topic and queues, so tests only publish and poll.
 * Queues are NOT purged by {@link TestDatabase#reset()} on purpose: tests on the shared
 * queues match their own {@code userId} and {@code eventId} and delete only what they
 * receive. A receive raises the receive count of every message it sees. Service containers
 * have queues of their own, not shared with harness tests.
 */
public final class EventBus {

    /** Same tag as docker-compose.yml's localstack service. */
    public static final String IMAGE = "localstack/localstack:4.14.0";
    public static final String TOPIC = "user-deleted";
    public static final String APPLICATIONS_QUEUE = "applications-user-deleted";
    public static final String MATCHING_QUEUE = "matching-user-deleted";
    public static final String APPLICATION_SERVICE_QUEUE = "application-service-user-deleted";
    /**
     * The shared queues the harness's own tests receive from.
     */
    public static final List<String> QUEUES = List.of(APPLICATIONS_QUEUE, MATCHING_QUEUE);
    /**
     * Queues a service container consumes (Day 25: application-service's, so its consumer never
     * takes the messages UserDeletedRelayIT and EventBusTest receive on the shared queues);
     * subscribed like QUEUES, but no harness test receives from them.
     */
    public static final List<String> CONTAINER_QUEUES = List.of(APPLICATION_SERVICE_QUEUE);
    public static final int MAX_RECEIVES = 5;

    private static final int PORT = 4566;
    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"));

    private static volatile GenericContainer<?> container;
    private static volatile SnsClient snsClient;
    private static volatile SqsClient sqsClient;
    private static volatile String topicArnValue;
    private static volatile Map<String, String> queueUrls;
    private static volatile URI endpointUri;

    private EventBus() {
    }

    /** The topic ARN for publishing. */
    public static String topicArn() {
        ensureStarted();
        return topicArnValue;
    }

    /** The URL of the named queue for polling. */
    public static String queueUrl(String name) {
        ensureStarted();
        return queueUrls.get(name);
    }

    /** The dead-letter queue name for a given queue. */
    public static String deadLetterQueue(String queue) {
        return queue + "-dlq";
    }

    /** A client on the emulator's mapped port, with dummy credentials. */
    public static SnsClient sns() {
        ensureStarted();
        return snsClient;
    }

    /** A client on the emulator's mapped port, with dummy credentials. */
    public static SqsClient sqs() {
        ensureStarted();
        return sqsClient;
    }

    /** The mapped host endpoint of the LocalStack container. */
    public static URI endpoint() {
        ensureStarted();
        return endpointUri;
    }

    /** Pauses the emulator: the bus is down, and a call to it hangs until it is unpaused. */
    public static void pause() {
        ensureStarted();
        DockerClientFactory.instance().client().pauseContainerCmd(container.getContainerId()).exec();
    }

    /** Unpauses the emulator. */
    public static void unpause() {
        ensureStarted();
        DockerClientFactory.instance().client().unpauseContainerCmd(container.getContainerId()).exec();
    }

    private static synchronized void ensureStarted() {
        if (queueUrls != null) {
            return;
        }

        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("SERVICES", "sns,sqs")
                .withEnv("AWS_DEFAULT_REGION", "eu-west-1")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(PORT));
        container.start();

        String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(PORT);
        endpointUri = URI.create(endpoint);

        snsClient = SnsClient.builder()
                .endpointOverride(EventBus.endpointUri)
                .region(Region.EU_WEST_1)
                .credentialsProvider(CREDENTIALS)
                .build();

        sqsClient = SqsClient.builder()
                .endpointOverride(EventBus.endpointUri)
                .region(Region.EU_WEST_1)
                .credentialsProvider(CREDENTIALS)
                .build();

        topicArnValue = snsClient.createTopic(t -> t.name(TOPIC)).topicArn();

        Map<String, String> local = new HashMap<>();
        for (String queueName : Stream.concat(QUEUES.stream(), CONTAINER_QUEUES.stream()).toList()) {
            String url = createQueueWithDeadLetter(sqsClient, queueName);
            local.put(queueName, url);

            String queueArn = sqsClient.getQueueAttributes(r -> r
                    .queueUrl(url)
                    .attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);

            snsClient.subscribe(r -> r
                    .topicArn(topicArnValue)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true")));
        }
        // Last: the guard above reads it, so a start that failed part-way is tried again.
        queueUrls = Map.copyOf(local);
    }

    /** Creates a queue and its dead-letter queue with a redrive policy, returns the queue URL. */
    static String createQueueWithDeadLetter(SqsClient client, String queueName) {
        return createQueueWithDeadLetter(client, queueName, Map.of());
    }

    /** Creates a queue and its DLQ, merging extra attributes into the main queue, returns the queue URL. */
    static String createQueueWithDeadLetter(SqsClient client, String queueName, Map<QueueAttributeName, String> extra) {
        String dlqName = deadLetterQueue(queueName);

        String dlqUrl = client.createQueue(r -> r.queueName(dlqName)).queueUrl();
        String dlqArn = client.getQueueAttributes(r -> r
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + MAX_RECEIVES + "\"}";
        Map<QueueAttributeName, String> attrs = new HashMap<>(extra);
        attrs.put(QueueAttributeName.REDRIVE_POLICY, redrivePolicy);
        return client.createQueue(r -> r
                .queueName(queueName)
                .attributes(attrs))
                .queueUrl();
    }

    /**
     * Creates a queue and its dead-letter queue for a consumer's own test, returns the queue URL.
     * 1 s visibility timeout so a failed message reaches the DLQ in about 5 s instead of over 2
     * minutes; neither takes nor is slowed by the shared queues.
     */
    public static String createOwnQueue(String queueName) {
        ensureStarted();
        return createQueueWithDeadLetter(sqsClient, queueName,
                Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1"));
    }

    /**
     * Approximate count of visible and in-flight messages in a queue.
     */
    public static int approximateCount(String queueUrl) {
        ensureStarted();
        var attrs = sqsClient.getQueueAttributes(r -> r
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .attributes();
        int visible = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        int notVisible = Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        return visible + notVisible;
    }

    /** The URL of a queue by its name, via getQueueUrl. */
    public static String queueUrlOf(String queueName) {
        ensureStarted();
        return sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
    }
}
