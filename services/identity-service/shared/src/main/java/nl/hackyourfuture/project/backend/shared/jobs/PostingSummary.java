package nl.hackyourfuture.project.backend.shared.jobs;

import java.time.LocalDate;
import java.util.List;

/**
 * What a saved job shows about its posting, and nothing more.
 *
 * <p>{@code location} is the mart's free-text column and {@code skills} is in the mart's own
 * order. Job search shows the normalised city and sorts the skills instead; Day 04 pinned both
 * differences, and this type carries the saved-jobs side of them unchanged.
 */
public record PostingSummary(
        String title,
        String companyName,
        String location,
        String workMode,
        Boolean isRemote,
        List<String> skills,
        String employmentType,
        LocalDate postedDate,
        String source,
        String category,
        String freshnessClass,
        Integer ageDays
) {
}
