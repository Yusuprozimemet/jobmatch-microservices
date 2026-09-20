package nl.hackyourfuture.project.backend.support;

import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * One HTTP response, kept as status plus raw body.
 *
 * <p>Raw, not deserialised into the application's DTOs: these tests assert on the wire
 * contract, and a test that maps the response with the same record the controller returned
 * cannot notice a renamed field.
 */
public record ApiResponse(int status, HttpHeaders headers, String body) {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** The body parsed as JSON. Fails the test if the body is absent or not JSON. */
    public JsonNode json() {
        if (body == null || body.isBlank()) {
            throw new AssertionError("Expected a JSON body but the response had none (status " + status + ")");
        }
        return JSON.readTree(body);
    }

    /**
     * A value addressed by JSON Pointer, e.g. {@code at("/content/0/postingId")}.
     * Returns a missing node rather than null when the path is absent.
     */
    public JsonNode at(String jsonPointer) {
        return json().at(jsonPointer);
    }

    /** The {@code Location} of a redirect. Fails the test if the response is not one. */
    public String location() {
        String location = headers.getFirst(HttpHeaders.LOCATION);
        if (location == null) {
            throw new AssertionError("Expected a Location header but the response had none (status " + status + ")");
        }
        return location;
    }

    public List<String> setCookieHeaders() {
        return headers.getOrEmpty(HttpHeaders.SET_COOKIE);
    }

    /** The whole {@code Set-Cookie} header for one cookie, attributes included. */
    public String setCookie(String name) {
        return setCookieHeaders().stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No Set-Cookie for '" + name + "' in " + setCookieHeaders()));
    }
}
