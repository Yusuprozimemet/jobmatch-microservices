package nl.hackyourfuture.project.backend.applications.events;

import nl.hackyourfuture.project.backend.applications.SavedJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

/**
 * Consumes user.deleted events from the applications queue (Day 27): reads messages from SQS,
 * deletes the user's saved jobs, and acknowledges the message. Idempotent: a repeat is a success,
 * and a failed message returns after the queue's visibility timeout and goes to the DLQ after 5
 * receives. Since Day 25 saved_jobs lives in apps_db, where no key reaches users: this consumer
 * is the only thing that removes a deleted user's rows.
 */
public class UserDeletedConsumer implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(UserDeletedConsumer.class);

    static final int WAIT_SECONDS = 5;
    static final int MAX_MESSAGES = 10;

    private final SavedJobRepository savedJobs;
    private final SqsClient sqs;
    private final String queueUrl;
    private final JsonMapper jsonMapper = new JsonMapper();
    private volatile boolean running = false;
    private Thread consumerThread;

    public UserDeletedConsumer(SavedJobRepository savedJobs, SqsClient sqs, String queueUrl) {
        this.savedJobs = savedJobs;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        consumerThread = new Thread(this::loop, "user-deleted-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(Duration.ofSeconds(WAIT_SECONDS + 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                var response = sqs.receiveMessage(r -> r
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(MAX_MESSAGES)
                        .waitTimeSeconds(WAIT_SECONDS));

                if (!running) {
                    break;
                }

                for (Message message : response.messages()) {
                    if (!running) {
                        break;
                    }
                    try {
                        handle(message.body());
                        sqs.deleteMessage(r -> r
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle()));
                    } catch (RuntimeException e) {
                        LOG.warn("user.deleted message {} not handled, left for redelivery: {}",
                                message.messageId(), e.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed to receive messages from user.deleted queue: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void handle(String body) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not JSON");
        }

        if (!node.isObject()) {
            throw new IllegalArgumentException("not an object");
        }

        JsonNode typeNode = node.path("type");
        if (!typeNode.isTextual() || !"user.deleted".equals(typeNode.asText())) {
            throw new IllegalArgumentException("type is not user.deleted");
        }

        JsonNode versionNode = node.path("version");
        if (!versionNode.isIntegralNumber() || versionNode.intValue() != 1) {
            throw new IllegalArgumentException("version is not 1");
        }

        JsonNode userIdNode = node.path("userId");
        if (!userIdNode.isTextual() || userIdNode.asText().isBlank()) {
            throw new IllegalArgumentException("userId missing");
        }

        UUID userId;
        try {
            userId = UUID.fromString(userIdNode.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("userId is not a UUID");
        }

        String eventId = node.path("eventId").asText(null);

        int removed = savedJobs.removeAllSavedJobs(userId);
        LOG.info("user.deleted {}: removed {} saved jobs of user {}", eventId, removed, userId);
    }
}
