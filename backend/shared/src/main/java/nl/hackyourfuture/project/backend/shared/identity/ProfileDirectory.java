package nl.hackyourfuture.project.backend.shared.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the ranking-relevant part of a user's profile.
 *
 * <p>How {@code matching} learns a user's skills and city without reading {@code identity}'s
 * {@code user_profiles} table. Two fields, not the row. Day 24 puts it behind HTTP when
 * {@code matching} runs as its own service; the shape stays.
 */
public interface ProfileDirectory {

    /** Empty when the user has never saved a profile. */
    Optional<ProfileSnapshot> forUser(UUID userId);
}
