package nl.hackyourfuture.project.backend.shared.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the ranking-relevant part of a user's profile.
 *
 * <p>How {@code matching} learns a user's skills and city without reading {@code identity}'s
 * {@code user_profiles} table. Two fields, not the row. Since Day 41 {@code matching} reads it
 * over HTTP, from identity's {@code /internal/profiles/{userId}}; the shape stayed.
 *
 * <p>matching-service's copy (Day 21): its image is built from its own folder and sees nothing of
 * {@code backend/}. The monolith's copy is the other half of the same contract; the tests that
 * cross into matching-service keep the two in step.
 */
public interface ProfileDirectory {

    /** Empty when the user has never saved a profile. */
    Optional<ProfileSnapshot> forUser(UUID userId);
}
