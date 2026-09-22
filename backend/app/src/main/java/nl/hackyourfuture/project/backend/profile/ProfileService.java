package nl.hackyourfuture.project.backend.profile;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.profile.dto.ProfileResponse;
import nl.hackyourfuture.project.backend.profile.dto.UpdateProfileRequest;
import nl.hackyourfuture.project.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;

    // New users get an empty profile, not a 404.
    public ProfileResponse getProfile(String email) {
        UUID userId = resolveUserId(email);
        Profile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> Profile.empty(userId));
        return ProfileResponse.from(profile);
    }

    // User comes from the session, not the request body, so nobody can edit another account.
    public ProfileResponse saveProfile(String email, UpdateProfileRequest request) {
        UUID userId = resolveUserId(email);

        // Re-check the minimum after cleanup: duplicates can drop a valid count too low.
        List<String> skills = normaliseSkills(request.skills());
        if (skills.size() < UpdateProfileRequest.MIN_SKILLS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select between " + UpdateProfileRequest.MIN_SKILLS + " and "
                            + UpdateProfileRequest.MAX_SKILLS + " skills. After removing blanks and "
                            + "duplicates you have " + skills.size() + ".");
        }

        Profile profile = Profile.builder()
                .userId(userId)
                .category(blankToNull(request.category()))
                .preferredCity(blankToNull(request.preferredCity()))
                .workMode(blankToNull(request.workMode()))
                .experienceLevel(blankToNull(request.experienceLevel()))
                .employmentType(blankToNull(request.employmentType()))
                .salaryPreference(request.salaryPreference())
                .skills(skills)
                .build();

        return ProfileResponse.from(profileRepository.save(profile));
    }

    private UUID resolveUserId(String email) {
        return userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))
                .getId();
    }

    // Blank means cleared - stored as null either way.
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    // Drops blanks and merges duplicates like "React"/"react" into one, keeping the
    // original spelling for display.
    private static List<String> normaliseSkills(List<String> skills) {
        if (skills == null) {
            return List.of();
        }

        Map<String, String> bySpelling = new LinkedHashMap<>();
        for (String skill : skills) {
            String trimmed = blankToNull(skill);
            if (trimmed != null) {
                bySpelling.putIfAbsent(canonicalise(trimmed), trimmed);
            }
        }
        return List.copyOf(bySpelling.values());
    }

    // Normalises for comparison: lowercase, hyphens and spaces collapsed to one space.
    private static String canonicalise(String skill) {
        return skill.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", " ");
    }
}
