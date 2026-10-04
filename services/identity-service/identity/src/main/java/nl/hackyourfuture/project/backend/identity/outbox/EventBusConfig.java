package nl.hackyourfuture.project.backend.identity.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * The outbox relay and its SNS client (Day 26). Off when {@code app.events.relay.enabled} is
 * false, as it is in the test harness: the run caches several contexts, and each one's relay
 * would take another test's outbox row.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.events.relay.enabled", havingValue = "true")
public class EventBusConfig {

    // Shorter than any test's wait, and it covers the SDK's retries, so a pass with the bus down
    // gives up on a row instead of hanging.
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    SnsClient snsClient(@Value("${app.events.sns.region}") String region,
                        @Value("${app.events.sns.endpoint:}") String endpoint,
                        @Value("${app.events.sns.access-key:}") String accessKey,
                        @Value("${app.events.sns.secret-key:}") String secretKey) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(PUBLISH_TIMEOUT));
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
    OutboxRelay outboxRelay(Outbox outbox,
                            SnsClient snsClient,
                            @Value("${app.events.user-deleted-topic-arn:}") String topicArn,
                            @Qualifier("identityTransactionManager") PlatformTransactionManager transactionManager) {
        if (topicArn.isBlank()) {
            throw new IllegalStateException(
                    "app.events.user-deleted-topic-arn is required while app.events.relay.enabled is true");
        }
        return new OutboxRelay(outbox, snsClient, topicArn, transactionManager);
    }
}
