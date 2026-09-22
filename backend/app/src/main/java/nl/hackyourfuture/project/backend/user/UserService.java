package nl.hackyourfuture.project.backend.user;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.user.dto.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    // The account comes from the session, so nobody can edit someone else's.
    // The email in the request is ignored: it is what the user logs in with.
    public UserResponse updateCurrentUser(String email, UserRequest request) {
        var existingUser = userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        var updatedUser = User.builder()
                .id(existingUser.getId())
                // Null name means leave it alone.
                .name(request.name() != null ? request.name() : existingUser.getName())
                .email(existingUser.getEmail())
                // Not editable: an edit must not erase or forge the agreement.
                .termsAcceptedAt(existingUser.getTermsAcceptedAt())
                // Read-only. Carried through so the response matches the row.
                .createdAt(existingUser.getCreatedAt())
                .oauthProvider(existingUser.getOauthProvider())
                .oauthProviderId(existingUser.getOauthProviderId())
                .passwordUpdatedAt(existingUser.getPasswordUpdatedAt())
                .build();
        var updated = userRepository.updateUser(updatedUser);
        return UserResponse.from(updated);
    }

    // for the frontend to verify active authentication.
    public UserResponse getUserByEmail(String email) {
        var user = userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "User not found"
                ));


        return UserResponse.from(user);
    }

    // Records the agreement. Registration does this itself; this covers Google sign-ups.
    public UserResponse acceptTerms(String email) {
        var user = userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        userRepository.acceptTerms(user.getId());
        return getUserByEmail(email);
    }

    // Deletes the caller's own account. Every table pointing at users cascades with it.
    public void deleteUserByEmail(String email) {
        var user = userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!userRepository.deleteUser(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

}
