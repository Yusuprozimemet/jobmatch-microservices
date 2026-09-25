package nl.hackyourfuture.project.backend.shared.internal;

import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The one place an internal client is built (Day 19): the {@code RestClient} that calls another
 * service's {@code /internal/**} routes, for the {@code PostingLookup}, {@code PostingShortlist} and
 * {@code SavedJobCounts} clients.
 *
 * <p>The URL is read on the first call, not when the bean is made: the server's port is known only
 * once it has started, and Day 17's harness sets the property from a container started after the
 * context. Empty means this process.
 *
 * <p>Connect 1 s and read 2 s, so top-matches fits under the gateway's 30 s read after the LLM's
 * 5 + 20 s. A read timeout must surface as a {@code ResourceAccessException}, the exception the
 * clients' fallbacks and breakers are typed to; {@code InternalClientsIT} holds that for a hang.
 *
 * <p>From a clone of the builder Spring injects: only that one is measured and traced (Day 38), and
 * it is one bean that every client would otherwise configure for all of them. Every request carries
 * a service token minted for it (Day 39).
 */
@Component
public final class InternalClients {

    private static final Duration CONNECT = Duration.ofSeconds(1);
    private static final Duration READ = Duration.ofSeconds(2);

    private final RestClient.Builder builder;
    private final ServiceToken serviceToken;
    private final Environment environment;

    public InternalClients(RestClient.Builder builder, ServiceToken serviceToken, Environment environment) {
        this.builder = builder;
        this.serviceToken = serviceToken;
        this.environment = environment;
    }

    /** A client for the service whose URL {@code property} holds, built on the first {@code get()}. */
    public Supplier<RestClient> forUrlProperty(String property) {
        return new MemoizedClientBuilder(property);
    }

    private class MemoizedClientBuilder implements Supplier<RestClient> {
        private final String property;
        private volatile RestClient client;

        MemoizedClientBuilder(String property) {
            this.property = property;
        }

        @Override
        public RestClient get() {
            if (client == null) {
                synchronized (this) {
                    if (client == null) {
                        client = buildClient();
                    }
                }
            }
            return client;
        }

        private RestClient buildClient() {
            String baseUrl = resolveBaseUrl();
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(CONNECT)
                    .build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(READ);

            return builder.clone()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory)
                    .requestInitializer(request -> {
                        String token = serviceToken.mint();
                        request.getHeaders().set("Authorization", "Bearer " + token);
                    })
                    .build();
        }

        private String resolveBaseUrl() {
            String propertyValue = environment.getProperty(property, "");
            if (propertyValue.isBlank()) {
                String port = environment.getRequiredProperty("local.server.port");
                return "http://localhost:" + port;
            }
            if (propertyValue.endsWith("/")) {
                return propertyValue.substring(0, propertyValue.length() - 1);
            }
            return propertyValue;
        }
    }
}
