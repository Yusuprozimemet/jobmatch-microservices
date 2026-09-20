package nl.hackyourfuture.project.backend.support;

import java.util.List;

/** A mart posting that exists in the database, as the builder stored it. */
public record TestPosting(String id, String title, String company,
                          List<String> cities, List<String> skills) {
}
