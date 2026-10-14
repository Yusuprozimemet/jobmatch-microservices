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
 * {@code users} and {@code user_profiles} tables themselves. Both now take the user's id from
 * the token's {@code sub}. The profile is the one remaining question, and this class answers it
 * through {@code /internal/profiles/{userId}}.
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
