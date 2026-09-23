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
 * principal has no user behind it, which happens when a session outlives its account. What that
 * means is the controller's call, not the resolver's: {@code applications} answers 404 and
 * {@code matching} answers 422, and {@code SessionWithoutAUserIT} pins both. A request with no
 * principal at all never gets this far; Spring Security answers it with 401 first.
 *
 * <p>Only {@code identity} knows how a principal becomes an id. Today that is one query by email.
 * Day 13 puts the id in the token, and the controllers using this do not change.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
