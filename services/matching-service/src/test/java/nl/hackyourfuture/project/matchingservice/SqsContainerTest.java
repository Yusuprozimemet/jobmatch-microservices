package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shows that the SQS container starts, stores messages, and drives failed messages to the dead-letter queue (Day 27). */
class SqsContainerTest {

    @Test
    void aMessageSentIsCountedUntilDeleted() {
        String queueName = "sqs-container-test-" + UUID.randomUUID();
        String queueUrl = SqsContainer.createOwnQueue(queueName);

        SqsContainer.sqs().sendMessage(r -> r.queueUrl(queueUrl).messageBody("test"));
        assertThat(SqsContainer.approximateCount(queueUrl)).isEqualTo(1);

        var message = SqsContainer.sqs().receiveMessage(r -> r
                .queueUrl(queueUrl)
                .waitTimeSeconds(1))
                .messages()
                .getFirst();
        SqsContainer.sqs().deleteMessage(r -> r
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle()));

        assertThat(SqsContainer.approximateCount(queueUrl)).isZero();
    }

    @Test
    void aMessageNeverDeletedReachesTheDeadLetterQueueWithinFifteenSeconds() throws InterruptedException {
        String queueName = "sqs-container-test-" + UUID.randomUUID();
        String queueUrl = SqsContainer.createOwnQueue(queueName);
        String dlqUrl = SqsContainer.queueUrlOf(SqsContainer.deadLetterQueue(queueName));

        SqsContainer.sqs().sendMessage(r -> r.queueUrl(queueUrl).messageBody("test"));
        long sentTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - sentTime < 15000) {
            SqsContainer.sqs().receiveMessage(r -> r
                    .queueUrl(queueUrl)
                    .waitTimeSeconds(1));
            int dlqCount = SqsContainer.approximateCount(dlqUrl);
            if (dlqCount == 1) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(SqsContainer.approximateCount(dlqUrl)).isEqualTo(1);
        assertThat(SqsContainer.approximateCount(queueUrl)).isZero();
    }
}
