package nl.hackyourfuture.project.backend.support;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records every database the run has connected to, so "one container for the whole run" is
 * something a test can assert rather than something the code merely intends.
 *
 * <p>The JDBC URL identifies the container: a second container would get a different mapped
 * port, since two containers cannot publish the same one.
 */
final class ObservedContainers {

    private static final Set<String> URLS = ConcurrentHashMap.newKeySet();

    private ObservedContainers() {
    }

    static Set<String> record() {
        URLS.add(PostgresContainer.jdbcUrl());
        return Set.copyOf(URLS);
    }
}
