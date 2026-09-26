package nl.hackyourfuture.project.backend.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Paginated wrapper response containing content and page metadata.
 *
 * <p>job-service's copy (Day 17): its image is built from its own folder and sees nothing of
 * {@code backend/}. The monolith's copy is the other half of the same contract; the tests that
 * cross into job-service keep the two in step.
 */
@Schema(description = "Paginated wrapper response containing content and page metadata")
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    // method to calculate total pages and wrap content with pagination metadata.
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
