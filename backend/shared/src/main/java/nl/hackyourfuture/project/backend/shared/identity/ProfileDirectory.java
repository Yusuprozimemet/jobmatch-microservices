package nl.hackyourfuture.project.backend.shared.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the ranking-relevant part of a user's profile.
 *
 * <p>Temporary, for the same reason as {@link UserDirectory}: {@code matching} uses
 * {@code ProfileRepository} directly today, which is {@code identity}'s table.
 *
 * <p>// TODO day-10: replace with whatever Day 10 settles on. It plans a {@code ProfileSkills}
 * interface with the same shape, so this may survive under another name.
 */
public interface ProfileDirectory {

    /** Empty when the user has never saved a profile. */
    Optional<ProfileSnapshot> forUser(UUID userId);
}
