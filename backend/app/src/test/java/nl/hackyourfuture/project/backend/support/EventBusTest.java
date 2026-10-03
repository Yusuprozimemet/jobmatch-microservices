package nl.hackyourfuture.project.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness's event bus (Day 26): a LocalStack emulator with SNS and SQS, one container per
 * run. These tests prove three things the spec says to stop and ask about if the image fails:
 * the emulator starts with no auth token, raw message delivery is honored, and redrive moves
 * messages to the dead-letter queue after five receives.
 */
class EventBusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theTopicDeliversTheBodyRawToBothQueues() throws Exception {
        String marker = UUID.randomUUID().toString();
        String body = MAPPER.writeValueAsString(Map.of("marker", marker));

        EventBus.sns().publish(r -> r
                .topicArn(EventBus.topicArn())
                .message(body));

        for (String queueName : EventBus.QUEUES) {
            String queueUrl = EventBus.queueUrl(queueName);
            Message msg = awaitMessage(queueUrl, body);
            assertThat(msg.body()).isEqualTo(body);
            EventBus.sqs().deleteMessage(r -> r
                    .queueUrl(queueUrl)
                    .receiptHandle(msg.receiptHandle()));
        }
    }

    @Test
    void eachQueueIsSubscribedRawWithADeadLetterQueueAfterFiveReceives() throws Exception {
        for (String queueName : Stream.concat(EventBus.QUEUES.stream(), EventBus.CONTAINER_QUEUES.stream()).toList()) {
            String queueUrl = EventBus.queueUrl(queueName);

            String redrivePolicy = EventBus.sqs().getQueueAttributes(r -> r
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.REDRIVE_POLICY))
                    .attributes()
                    .get(QueueAttributeName.REDRIVE_POLICY);

            JsonNode policy = MAPPER.readTree(redrivePolicy);
            assertThat(policy.get("maxReceiveCount").asInt()).isEqualTo(EventBus.MAX_RECEIVES);
            assertThat(policy.get("deadLetterTargetArn").asText()).endsWith(":" + EventBus.deadLetterQueue(queueName));

            String queueArn = EventBus.sqs().getQueueAttributes(r -> r
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);

            var subscriptions = EventBus.sns().listSubscriptionsByTopic(r -> r.topicArn(EventBus.topicArn()))
                    .subscriptions();
            var subscription = subscriptions.stream()
                    .filter(s -> s.endpoint().equals(queueArn))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No subscription found for queue " + queueName + " (arn: " + queueArn + ")"));

            var attrs = EventBus.sns().getSubscriptionAttributes(r -> r.subscriptionArn(subscription.subscriptionArn()))
                    .attributes();
            assertThat(attrs.get("RawMessageDelivery")).isEqualTo("true");
        }
    }

    @Test
    void theEmulatorMovesAMessageToTheDeadLetterQueueAfterFiveReceives() {
        String scratchQueueName = "harness-redrive-" + UUID.randomUUID().toString().substring(0, 8);
        String scratchQueueUrl = EventBus.createQueueWithDeadLetter(EventBus.sqs(), scratchQueueName);
        String dlqName = EventBus.deadLetterQueue(scratchQueueName);
        String dlqUrl = EventBus.sqs().getQueueUrl(r -> r.queueName(dlqName)).queueUrl();

        String testMessage = "test-message-" + UUID.randomUUID();
        EventBus.sqs().sendMessage(r -> r
                .queueUrl(scratchQueueUrl)
                .messageBody(testMessage));

        for (int i = 0; i < EventBus.MAX_RECEIVES; i++) {
            var messages = EventBus.sqs().receiveMessage(r -> r
                    .queueUrl(scratchQueueUrl)
                    .maxNumberOfMessages(10)
                    .visibilityTimeout(0)
                    .waitTimeSeconds(1))
                    .messages();
            assertThat(messages).extracting(Message::body).containsExactly(testMessage);
        }

        var messages = EventBus.sqs().receiveMessage(r -> r
                .queueUrl(scratchQueueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1))
                .messages();
        assertThat(messages).isEmpty();

        Message dlqMsg = awaitMessage(dlqUrl, testMessage);
        assertThat(dlqMsg.body()).isEqualTo(testMessage);

        EventBus.sqs().deleteQueue(r -> r.queueUrl(scratchQueueUrl));
        EventBus.sqs().deleteQueue(r -> r.queueUrl(dlqUrl));
    }

    private static Message awaitMessage(String queueUrl, String expectedBody) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var messages = EventBus.sqs().receiveMessage(r -> r
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1))
                    .messages();

            var found = messages.stream()
                    .filter(m -> m.body().equals(expectedBody))
                    .findFirst();

            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new AssertionError("Message not found within 5 seconds: " + expectedBody);
    }
}
