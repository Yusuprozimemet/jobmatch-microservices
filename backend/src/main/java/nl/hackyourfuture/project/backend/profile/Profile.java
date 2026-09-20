package nl.hackyourfuture.project.backend.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// A user's saved job preferences. One row per user, or none until they save.
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class Profile {
    private UUID userId;
    // Stored exactly as sent.
    private String category;
    private String preferredCity;
    private String workMode;
    private String experienceLevel;
    private String employmentType;
    // DB column is "salary".
    private BigDecimal salaryPreference;
    // Never null; empty means no skills picked yet.
    private List<String> skills;

    // Default profile for a user who hasn't saved one yet.
    public static Profile empty(UUID userId) {
        return Profile.builder()
                .userId(userId)
                .skills(List.of())
                .build();
    }
}
