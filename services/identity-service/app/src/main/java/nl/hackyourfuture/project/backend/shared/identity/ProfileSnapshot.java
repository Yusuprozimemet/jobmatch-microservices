package nl.hackyourfuture.project.backend.shared.identity;

import java.util.List;

/**
 * The part of a profile that ranking needs: what the user can do, and where they want to do it.
 *
 * <p>Deliberately not the whole profile. {@code matching} reads two fields, so it is handed two
 * fields — a snapshot that names them is a boundary, where passing the entity would be the
 * coupling with extra steps.
 */
public record ProfileSnapshot(List<String> skills, String preferredCity) {

    /**
     * The floor a profile has to clear to be matched on, and the same number the profile form
     * enforces when saving. One constant rather than two, because two would drift and the
     * symptom would be a form that accepts a profile matching then refuses.
     */
    public static final int MINIMUM_SKILLS = 5;
}
