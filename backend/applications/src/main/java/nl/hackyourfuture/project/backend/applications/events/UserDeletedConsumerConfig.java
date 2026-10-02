package nl.hackyourfuture.project.backend.applications.events;

import nl.hackyourfuture.project.backend.applications.SavedJobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * The user.deleted consumer and its SQS client (Day 27). Off when {@code app.events.consumer.enabled}
 * is false, as it is in the test harness: the run caches several contexts, and each one's consumer
 * would take another test's message from the shared queue.
 */
@Configuration
@ConditionalOnProperty(name = "app.events.consumer.enabled", havingValue = "true")
public class UserDeletedConsumerConfig {

    @Bean
    SqsClient applicationsSqsClient(@Value("${app.events.sqs.region}") String region,
                                   @Value("${app.events.sqs.endpoint:}") String endpoint,
                                   @Value("${app.events.sqs.access-key:}") String accessKey,
                                   @Value("${app.events.sqs.secret-key:}") String secretKey) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(
                        Duration.ofSeconds(UserDeletedConsumer.WAIT_SECONDS + 5)));
        // Set for the emulator; empty is AWS itself.
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Bean
    UserDeletedConsumer userDeletedConsumer(SavedJobRepository savedJobs,
                                           @Qualifier("applicationsSqsClient") SqsClient sqs,
                                           @Value("${app.events.user-deleted-queue-url:}") String queueUrl) {
        if (queueUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.events.user-deleted-queue-url is required while app.events.consumer.enabled is true");
        }
        return new UserDeletedConsumer(savedJobs, sqs, queueUrl);
    }
}
