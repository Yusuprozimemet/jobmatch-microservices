package nl.hackyourfuture.project.backend.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads a SQL fixture off the test classpath. */
final class SqlScripts {

    private SqlScripts() {
    }

    static String read(String classpathLocation) {
        try (InputStream stream = SqlScripts.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (stream == null) {
                throw new IllegalStateException("No such SQL fixture on the classpath: " + classpathLocation);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SQL fixture " + classpathLocation, e);
        }
    }
}
