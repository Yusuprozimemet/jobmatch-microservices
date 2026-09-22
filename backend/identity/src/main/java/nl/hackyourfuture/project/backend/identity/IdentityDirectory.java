package nl.hackyourfuture.project.backend.identity;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.identity.profile.ProfileRepository;
import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.identity.user.UserRepository;
import nl.hackyourfuture.project.backend.shared.identity.ProfileDirectory;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import nl.hackyourfuture.project.backend.shared.identity.UserDirectory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules are allowed to know about a user.
 *
 * <p>Package-private on purpose: nothing outside {@code identity} names this class, only the
 * two interfaces it implements. That is the whole point — {@code applications} and
 * {@code matching} used to reach in and read the {@code users} and {@code user_profiles}
 * tables themselves, and now they ask through a front door narrow enough to see through.
 *
 * <p>One class for both interfaces because they are one idea, not two: this is the list of
 * questions identity answers for everyone else, and it should stay short enough to read.
 */
@Component
@RequiredArgsConstructor
class IdentityDirectory implements UserDirectory, ProfileDirectory {

    private final UserRepository users;
    private final ProfileRepository profiles;

    @Override
    public Optional<UUID> findUserIdByEmail(String email) {
        return users.getUserByEmail(email).map(User::getId);
    }

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
