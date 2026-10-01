package nl.hackyourfuture.project.backend.events;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting an account leaves a {@code user.deleted} row in {@code identity.outbox}, committed with
 * the delete or not at all (Day 26). The relay publishes the row; an event for a delete that
 * rolled back, or a delete with no event, is what this is for.
 *
 * <p>The failures are made in the database, through identity's own pool so that identity_user
 * owns what the test creates, as {@code ModuleConnectionsIT} does. Each case fails one of the two
 * statements, so whichever of them the code runs first, one case catches a missing transaction.
 */
class UserDeletedOutboxIT extends IntegrationTest {

    @Autowired @Qualifier("identityDataSource") private DataSource identity;

    @Test
    void deletingTheAccountLeavesOneUserDeletedRow() {
        TestUser user = aUser().create();

        assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(204);

        assertThat(outboxTypesOf(user.id())).containsExactly("user.deleted");
    }

    // A key without ON DELETE refuses to let the user go.
    @Test
    void aDeleteThatFailsLeavesNoOutboxRow() {
        TestUser user = aUser().create();
        JdbcClient owner = JdbcClient.create(identity);
        owner.sql("CREATE TABLE delete_blocker (user_id UUID NOT NULL REFERENCES users(id))").update();
        try {
            owner.sql("INSERT INTO delete_blocker (user_id) VALUES (:userId)")
                    .param("userId", user.id())
                    .update();

            assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(500);

            assertThat(userExists(user.id())).isTrue();
            assertThat(outboxTypesOf(user.id())).isEmpty();
        } finally {
            owner.sql("DROP TABLE delete_blocker").update();
        }
    }

    // Only this user's row is refused, so a delete in another test is untouched. DDL takes no
    // parameters; the id is a UUID the test made.
    @Test
    void anOutboxInsertThatFailsLeavesTheUserAndTheirSavedJobs() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0002"));
        assertThat(savedJobsOf(user.id())).isEqualTo(2);
        JdbcClient owner = JdbcClient.create(identity);
        owner.sql("""
                CREATE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'outbox insert refused by the test';
                END;
                $$ LANGUAGE plpgsql
                """).update();
        try {
            owner.sql("""
                    CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox FOR EACH ROW
                    WHEN (NEW.user_id = '%s'::uuid) EXECUTE FUNCTION fail_outbox()
                    """.formatted(user.id())).update();

            assertThat(client.delete("/api/users/me").status()).isEqualTo(500);

            assertThat(userExists(user.id())).isTrue();
            assertThat(savedJobsOf(user.id())).isEqualTo(2);
            assertThat(outboxTypesOf(user.id())).isEmpty();
        } finally {
            owner.sql("DROP FUNCTION fail_outbox() CASCADE").update();
        }
    }

    private List<String> outboxTypesOf(UUID userId) {
        return jdbc().sql("SELECT type FROM identity.outbox WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    private boolean userExists(UUID userId) {
        return jdbc().sql("SELECT EXISTS (SELECT 1 FROM identity.users WHERE id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    private long savedJobsOf(UUID userId) {
        return jdbc().sql("SELECT count(*) FROM saved_jobs WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
