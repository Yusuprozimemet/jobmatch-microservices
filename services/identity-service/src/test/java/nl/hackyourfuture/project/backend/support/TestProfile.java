package nl.hackyourfuture.project.backend.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A profile that exists in the database, as the builder stored it. */
public record TestProfile(UUID userId, String category, String preferredCity, String workMode,
                          String experienceLevel, String employmentType, BigDecimal salary,
                          List<String> skills) {
}
