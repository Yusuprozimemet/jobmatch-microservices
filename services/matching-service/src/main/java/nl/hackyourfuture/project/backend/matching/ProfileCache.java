package nl.hackyourfuture.project.backend.matching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 24 Track B. Profile skills cached for seconds, keyed by user, because Track A measured
 * the profile call at 16% of top-matches' server time (5.7-7.2 ms of 34.9-43.9 ms, without the
 * model), above the 10% the spec set before measuring. Only a profile that can be ranked is
 * cached (JobMatchService puts it after the five-skill check): no profile (422) and too few
 * skills (422) are asked again each time, so a user who has just filled in or fixed a profile is
 * not refused on the old one. The price: a profile edit to an already rankable profile is not
 * seen by matching for up to the window. The existence call is never cached (InternalUserController:
 * a cached answer lets a deleted user in), and it runs before this cache is read. Written on
 * first fetch only, never refreshed on a hit, so the window is from the fetch.
 */
@Component
class ProfileCache {

    private final Cache<UUID, ProfileSnapshot> cache;

    ProfileCache(@Value("${app.profile-cache.window}") Duration window, ObjectProvider<Ticker> ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(10_000)
                .ticker(ticker.getIfAvailable(Ticker::systemTicker))
                .build();
    }

    Optional<ProfileSnapshot> get(UUID userId) {
        return Optional.ofNullable(cache.getIfPresent(userId));
    }

    void put(UUID userId, ProfileSnapshot profile) {
        cache.put(userId, profile);
    }
}
