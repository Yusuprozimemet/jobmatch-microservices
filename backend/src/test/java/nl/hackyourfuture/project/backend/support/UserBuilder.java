package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds a user straight into the database. Start one with {@code aUser()}. */
public final class UserBuilder {

    /** The password every test user gets unless the test asks for another one. */
    public static final String DEFAULT_PASSWORD = "Password123!";

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    // BCrypt costs about 100ms per hash by design. Nearly every user in the suite has the
    // same password, so hashing it once keeps that out of the suite's runtime.
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final JdbcClient jdbc;

    private String email;
    private String name = "Test User";
    private String password = DEFAULT_PASSWORD;
    private boolean termsAccepted = true;
    private String oauthProvider;
    private String oauthProviderId;

    UserBuilder(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.email = "user-" + COUNTER.incrementAndGet() + "@example.test";
    }

    public UserBuilder email(String value) {
        // The application lowercases on the way in and looks up by exact match, so a fixture
        // with a capital letter would create a user nobody can log in as.
        this.email = value.toLowerCase(Locale.ROOT);
        return this;
    }

    public UserBuilder name(String value) {
        this.name = value;
        return this;
    }

    public UserBuilder password(String value) {
        this.password = value;
        return this;
    }

    /** A Google-only account: no credentials row, so no password login. */
    public UserBuilder googleAccount(String providerId) {
        this.oauthProvider = "google";
        this.oauthProviderId = providerId;
        this.password = null;
        return this;
    }

    /** Registered but never agreed to the terms - the frontend shows the terms screen. */
    public UserBuilder withoutAcceptedTerms() {
        this.termsAccepted = false;
        return this;
    }

    public TestUser create() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users (id, email, name, oauth_provider, oauth_provider_id, terms_accepted_at)
                        VALUES (:id, :email, :name, :provider, :providerId, :termsAcceptedAt)
                        """)
                .param("id", id)
                .param("email", email)
                .param("name", name)
                .param("provider", oauthProvider)
                .param("providerId", oauthProviderId)
                .param("termsAcceptedAt", termsAccepted ? OffsetDateTime.now() : null)
                .update();

        if (password != null) {
            jdbc.sql("INSERT INTO user_credentials (user_id, password_hash) VALUES (:userId, :hash)")
                    .param("userId", id)
                    .param("hash", HASHES.computeIfAbsent(password, ENCODER::encode))
                    .update();
        }

        return new TestUser(id, email, name, password);
    }
}
