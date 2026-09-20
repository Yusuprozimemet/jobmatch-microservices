package nl.hackyourfuture.project.backend.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProfileRepository {
    private final JdbcClient jdbcClient;

    private static final String PROFILE_SELECT = """
            SELECT user_id, category, preferred_city, work_mode,
                   experience_level, employment_type, salary, skills
            FROM user_profiles
            """;

    private static final RowMapper<Profile> PROFILE_ROW_MAPPER = (rs, _) -> Profile.builder()
            .userId(rs.getObject("user_id", UUID.class))
            .category(rs.getString("category"))
            .preferredCity(rs.getString("preferred_city"))
            .workMode(rs.getString("work_mode"))
            .experienceLevel(rs.getString("experience_level"))
            .employmentType(rs.getString("employment_type"))
            .salaryPreference(rs.getBigDecimal("salary"))
            .skills(readSkills(rs.getArray("skills")))
            .build();

    // Guards against old rows saved before skills was required.
    private static List<String> readSkills(Array skills) throws java.sql.SQLException {
        if (skills == null) {
            return List.of();
        }
        return List.of((String[]) skills.getArray());
    }

    // Empty if the user has never saved a profile.
    public Optional<Profile> findByUserId(UUID userId) {
        return jdbcClient.sql(PROFILE_SELECT + " WHERE user_id = :userId")
                .param("userId", userId)
                .query(PROFILE_ROW_MAPPER)
                .optional();
    }

    // Insert or replace - no separate create step, so a save always overwrites every field.
    public Profile save(Profile profile) {
        // Returns the row as stored, e.g. salary comes back formatted like 45000.00.
        return jdbcClient.sql("""
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
                        RETURNING user_id, category, preferred_city, work_mode,
                                  experience_level, employment_type, salary, skills
                        """)
                .param("userId", profile.getUserId())
                .param("category", profile.getCategory())
                .param("preferredCity", profile.getPreferredCity())
                .param("workMode", profile.getWorkMode())
                .param("experienceLevel", profile.getExperienceLevel())
                .param("employmentType", profile.getEmploymentType())
                .param("salary", profile.getSalaryPreference())
                .param("skills", profile.getSkills().toArray(String[]::new))
                .query(PROFILE_ROW_MAPPER)
                .single();
    }
}
