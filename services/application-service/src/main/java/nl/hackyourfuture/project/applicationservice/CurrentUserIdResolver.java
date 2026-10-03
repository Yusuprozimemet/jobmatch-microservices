package nl.hackyourfuture.project.applicationservice;

import nl.hackyourfuture.project.backend.shared.web.CurrentUserId;
import nl.hackyourfuture.project.backend.shared.web.TokenSubject;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the request's token subject into a user id for {@link CurrentUserId} parameters.
 *
 * <p>Package-private: other modules name the annotation, never this class. The id is the verified
 * token's {@code sub} (Day 41), not the monolith's lookup by email. Identity is asked
 * once per parameter whether the user still exists — the deleted-user rule (Day 39): a token
 * outlives its account, and an empty value is what the controller answers 404 "User not found" for.
 *
 * <p>It never chooses a status for a deleted user; it returns an empty value and the controller
 * decides. Identity's outage throws a 503. The 401 for no principal is a guard only: Spring Security
 * rejects those requests before any controller runs.
 */
@Component
class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

    private static final ResolvableType OPTIONAL_UUID =
            ResolvableType.forClassWithGenerics(Optional.class, UUID.class);

    private final UserExistenceClient existence;

    CurrentUserIdResolver(UserExistenceClient existence) {
        this.existence = existence;
    }

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
        UUID id = TokenSubject.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        return existence.exists(id) ? Optional.of(id) : Optional.empty();
    }
}
