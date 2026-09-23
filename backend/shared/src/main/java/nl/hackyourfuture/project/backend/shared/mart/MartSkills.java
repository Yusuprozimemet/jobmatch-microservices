package nl.hackyourfuture.project.backend.shared.mart;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

// Parses the skills column, e.g. '["python","sql"]'.
// Takes a String, not a richer type, so a column type change breaks the build here instead
// of failing silently downstream. The match shortlist in JobsDirectory parses skills in SQL instead - it needs
// them there for ranking and dedup.
public final class MartSkills {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private MartSkills() {
    }

    public static List<String> parse(String skillsColumn) {
        if (skillsColumn == null || skillsColumn.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(skillsColumn, STRING_LIST).stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (JacksonException e) {
            // Not JSON - probably an old, comma-separated value like "python, sql".
            return splitOnCommas(skillsColumn);
        }
    }

    private static List<String> splitOnCommas(String text) {
        return Arrays.stream(text.replace("[", "").replace("]", "").replace("\"", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}