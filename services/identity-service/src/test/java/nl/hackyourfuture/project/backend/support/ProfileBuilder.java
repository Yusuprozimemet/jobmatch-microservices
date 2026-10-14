package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Builds a saved profile for a user. Start one with {@code aProfile()}. */
public final class ProfileBuilder {

    private final JdbcClient jdbc;

    private UUID userId;
    private String category = "software_engineering";
    private String preferredCity = "Amsterdam";
    private String workMode = "hybrid";
    private String experienceLevel = "medior";
    private String employmentType = "full_time";
    private BigDecimal salary = new BigDecimal("4500.00");
    private List<String> skills = List.of("java", "sql");

    ProfileBuilder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProfileBuilder forUser(TestUser user) {
        this.userId = user.id();
        return this;
    }

    public ProfileBuilder forUser(UUID value) {
        this.userId = value;
        return this;
    }

    public ProfileBuilder category(String value) {
        this.category = value;
        return this;
    }

    public ProfileBuilder preferredCity(String value) {
        this.preferredCity = value;
        return this;
    }

    public ProfileBuilder workMode(String value) {
        this.workMode = value;
        return this;
    }

    public ProfileBuilder experienceLevel(String value) {
        this.experienceLevel = value;
        return this;
    }

    public ProfileBuilder employmentType(String value) {
        this.employmentType = value;
        return this;
    }

    public ProfileBuilder salary(String value) {
        this.salary = new BigDecimal(value);
        return this;
    }

    /** Matching compares these against the mart's skills, which are lowercase. */
    public ProfileBuilder skills(String... values) {
        this.skills = List.of(values);
        return this;
    }

    public TestProfile create() {
        if (userId == null) {
            throw new IllegalStateException("aProfile() needs a user: call forUser(...) before create()");
        }
        jdbc.sql("""
                        INSERT INTO user_profiles (user_id, category, preferred_city, work_mode,
                                                   experience_level, employment_type, salary, skills)
                        VALUES (:userId, :category, :preferredCity, :workMode,
                                :experienceLevel, :employmentType, :salary, :skills)
                        ON CONFLICT (user_id) DO UPDATE SET
                            category = EXCLUDED.category,
                            preferred_city = EXCLUDED.preferred_city,
                            work_mode = EXCLUDED.work_mode,
                            experience_level = EXCLUDED.experience_level,
                            employment_type = EXCLUDED.employment_type,
                            salary = EXCLUDED.salary,
                            skills = EXCLUDED.skills
                        """)
                .param("userId", userId)
                .param("category", category)
                .param("preferredCity", preferredCity)
                .param("workMode", workMode)
                .param("experienceLevel", experienceLevel)
                .param("employmentType", employmentType)
                .param("salary", salary)
                .param("skills", skills.toArray(String[]::new))
                .update();

        return new TestProfile(userId, category, preferredCity, workMode,
                experienceLevel, employmentType, salary, skills);
    }
}
