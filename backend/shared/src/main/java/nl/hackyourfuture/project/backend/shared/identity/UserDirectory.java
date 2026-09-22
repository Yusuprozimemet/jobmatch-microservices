package nl.hackyourfuture.project.backend.shared.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the email of an authenticated caller into the user it belongs to.
 *
 * <p>Temporary. It exists because {@code applications} and {@code matching} both resolve an
 * email themselves today, which means both read the {@code users} table that {@code identity}
 * owns. This interface moves that read behind a front door so the code can be split now;
 * it does not fix the design.
 *
 * <p>// TODO day-10: delete this. Day 10 resolves the principal once at the edge and passes a
 * {@code UUID userId} down, so nothing below a controller needs to look a user up at all.
 */
public interface UserDirectory {

    /** Empty when no account has that email. The caller decides what that means. */
    Optional<UUID> findUserIdByEmail(String email);
}
