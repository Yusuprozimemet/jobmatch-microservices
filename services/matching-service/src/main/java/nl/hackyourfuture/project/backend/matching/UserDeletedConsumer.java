package nl.hackyourfuture.project.backend.matching;

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
 * Consumes user.deleted from matching's queue (Day 27) and evicts the user's cached profile;
 * idempotent: a repeat is a success, never an exception. A failed message comes back after the
 * visibility timeout and goes to the DLQ after 5 receives. The existence call to identity, not
 * this, is what refuses a deleted user at once (ProfileCache), this removes the stored skills.
 */
class UserDeletedConsumer implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(UserDeletedConsumer.class);

    static final int WAIT_SECONDS = 5;
    static final int MAX_MESSAGES = 10;

    private final ProfileCache profiles;
    private final SqsClient sqs;
    private final String queueUrl;
    private final JsonMapper jsonMapper = new JsonMapper();
    private volatile boolean running = false;
    private Thread consumerThread;

    UserDeletedConsumer(ProfileCache profiles, SqsClient sqs, String queueUrl) {
        this.profiles = profiles;
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

        profiles.evict(userId);
        LOG.info("user.deleted {}: evicted cached profile of user {}", eventId, userId);
    }
}
