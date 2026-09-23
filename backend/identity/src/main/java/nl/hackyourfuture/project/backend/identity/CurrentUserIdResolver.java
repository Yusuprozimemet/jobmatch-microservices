package nl.hackyourfuture.project.backend.identity;

import lombok.RequiredArgsConstructor;
import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.identity.user.UserRepository;
import nl.hackyourfuture.project.backend.shared.web.CurrentUserId;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the request's principal into a user id for {@link CurrentUserId} parameters.
 *
 * <p>Package-private, like {@link IdentityDirectory}: other modules name the annotation, never
 * this class. It reads the principal the way the four controllers' copied helpers did (a plain
 * email, or a {@code UserDetails} username) and asks identity's own repository, one query.
 *
 * <p>It never chooses a status for a principal with no user; it returns an empty value and the
 * controller decides. The 401 for no principal is a guard only: Spring Security rejects those
 * requests before any controller runs.
 */
@Component
@RequiredArgsConstructor
class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

    private static final ResolvableType OPTIONAL_UUID =
            ResolvableType.forClassWithGenerics(Optional.class, UUID.class);

    private final UserRepository users;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class);
    }

    @Override
    public Optional<UUID> resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (!OPTIONAL_UUID.isAssignableFrom(ResolvableType.forMethodParameter(parameter))) {
            throw new IllegalStateException("@CurrentUserId goes on an Optional<UUID> parameter, not on "
                    + parameter.getGenericParameterType() + " in " + parameter.getExecutable());
        }
        String email = email(SecurityContextHolder.getContext().getAuthentication())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        return users.getUserByEmail(email).map(User::getId);
    }

    private static Optional<String> email(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof String email) {
            if ("anonymousUser".equals(email) || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(email);
        }
        if (principal instanceof UserDetails userDetails) {
            return Optional.of(userDetails.getUsername());
        }
        return Optional.empty();
    }
}
