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

/**
 * The event bus (Day 26): a LocalStack container with SNS and SQS, one for the whole run,
 * Ryuk removes it. The bus creates the topic and queues, so tests only publish and poll.
 * Queues are NOT purged by {@link TestDatabase#reset()} on purpose: tests on the shared
 * queues match their own {@code userId} and {@code eventId} and delete only what they
 * receive. A receive raises the receive count of every message it sees.
 */
public final class EventBus {

    /** Same tag as docker-compose.yml's localstack service. */
    public static final String IMAGE = "localstack/localstack:4.14.0";
    public static final String TOPIC = "user-deleted";
    public static final String APPLICATIONS_QUEUE = "applications-user-deleted";
    public static final String MATCHING_QUEUE = "matching-user-deleted";
    public static final List<String> QUEUES = List.of(APPLICATIONS_QUEUE, MATCHING_QUEUE);
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
        for (String queueName : QUEUES) {
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
        String dlqName = deadLetterQueue(queueName);

        String dlqUrl = client.createQueue(r -> r.queueName(dlqName)).queueUrl();
        String dlqArn = client.getQueueAttributes(r -> r
                .queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + MAX_RECEIVES + "\"}";
        return client.createQueue(r -> r
                .queueName(queueName)
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy)))
                .queueUrl();
    }
}
