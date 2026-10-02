package nl.hackyourfuture.project.backend.matching;

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
 * is false, as in every test context but UserDeletedConsumerTest's and in the backend harness's
 * matching-service container. On, it requires the queue URL.
 */
@Configuration
@ConditionalOnProperty(name = "app.events.consumer.enabled", havingValue = "true")
public class UserDeletedConsumerConfig {

    @Bean
    SqsClient eventsSqsClient(@Value("${app.events.sqs.region}") String region,
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
    UserDeletedConsumer userDeletedConsumer(ProfileCache profiles,
                                            @Qualifier("eventsSqsClient") SqsClient sqs,
                                            @Value("${app.events.user-deleted-queue-url:}") String queueUrl) {
        if (queueUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.events.user-deleted-queue-url is required while app.events.consumer.enabled is true");
        }
        return new UserDeletedConsumer(profiles, sqs, queueUrl);
    }
}
