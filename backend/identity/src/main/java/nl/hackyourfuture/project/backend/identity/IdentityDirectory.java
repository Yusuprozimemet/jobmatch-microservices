package nl.hackyourfuture.project.backend.identity;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.identity.profile.ProfileRepository;
import nl.hackyourfuture.project.backend.shared.identity.ProfileDirectory;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules are allowed to know about a user.
 *
 * <p>Package-private on purpose: nothing outside {@code identity} names this class, only the
 * interface it implements. {@code applications} and {@code matching} used to read the
 * {@code users} and {@code user_profiles} tables themselves. Since Day 10 they receive the user's
 * id from {@link CurrentUserIdResolver} and ask nothing about users at all; the profile is the
 * one question left, and this is where it is answered.
 */
@Component
@RequiredArgsConstructor
class IdentityDirectory implements ProfileDirectory {

    private final ProfileRepository profiles;

    /**
     * Two fields of the profile, never the row. A caller that wanted the rest would be asking
     * identity to be its data access layer again.
     */
    @Override
    public Optional<ProfileSnapshot> forUser(UUID userId) {
        return profiles.findByUserId(userId)
                .map(profile -> new ProfileSnapshot(profile.getSkills(), profile.getPreferredCity()));
    }
}
