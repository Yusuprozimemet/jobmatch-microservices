package nl.hackyourfuture.project.backend.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class User {
    private UUID id;
    private String email;
    private String name;
    private OffsetDateTime termsAcceptedAt;
    private OffsetDateTime createdAt;
    // Null for a password-only account; "google" once an identity is linked.
    private String oauthProvider;
    // Which Google account. Part of the user's data export.
    private String oauthProviderId;
    // When the password was last set. Null for Google-only accounts.
    private OffsetDateTime passwordUpdatedAt;
}

