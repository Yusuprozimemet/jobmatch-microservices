package nl.hackyourfuture.project.gateway;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The rate limit on the credential routes (Day 15): per client, in memory. Correct for one gateway
 * replica; more than one needs a shared store.
 */
@Configuration(proxyBeanMethods = false)
class RateLimit {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * The buckets the gateway's Bucket4j filter uses. Caffeine drops a client's bucket once it has
     * refilled, so the map holds only clients seen in the last minute.
     */
    @Bean
    AsyncProxyManager<String> rateLimitBuckets() {
        return Bucket4jCaffeine.<String>builderFor(Caffeine.newBuilder().maximumSize(100_000))
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ZERO))
                .build()
                .asAsync();
    }

    /**
     * Who the client is: the connecting address, or, when that is a trusted proxy (the frontend,
     * from Day 16), the address the proxy added last to {@code X-Forwarded-For}. A client's own
     * {@code X-Forwarded-For} is not believed, or it could pick a new bucket for every request.
     */
    static Function<ServerRequest, String> client(String trustedProxies) {
        Pattern trusted = trustedProxies.isBlank() ? null : Pattern.compile(trustedProxies);
        return request -> {
            String remote = request.servletRequest().getRemoteAddr();
            if (trusted == null || !trusted.matcher(remote).matches()) {
                return remote;
            }
            List<String> forwarded = request.headers().header(FORWARDED_FOR);
            if (forwarded.isEmpty()) {
                return remote;
            }
            String[] hops = forwarded.getLast().split(",");
            return hops[hops.length - 1].trim();
        };
    }
}
