package nl.hackyourfuture.project.backend.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The id of the user making the request, resolved once, at the edge, by {@code identity}.
 *
 * <p>Goes on an {@code Optional<UUID>} controller parameter. It is empty when the request's
 * principal has no user behind it, which happens when a session outlives its account.
 * {@code applications} answers 404 for an empty value. {@code matching}, since Day 41, takes
 * the token's {@code sub} ({@code TokenSubject}) and asks identity whether the user exists.
 * {@code SessionWithoutAUserIT} pins both. A request with no principal at all never gets this
 * far; Spring Security answers it with 401 first.
 *
 * <p>Only {@code identity} knows how a principal becomes an id. It is one query by the token's
 * email. Day 13 put the id in the token and kept the lookup on purpose: it is what refuses a
 * user deleted while their token is still valid.
 *
 * <p>application-service's copy (Day 25): its image is built from its own folder and sees nothing of
 * {@code backend/}. Here identity is another service: the resolver that fills it from the token's
 * {@code sub} and identity's existence check arrives with the existence client, before the controller
 * moves in.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
