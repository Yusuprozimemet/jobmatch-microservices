package nl.hackyourfuture.project.backend.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP client for the running application, with a cookie jar.
 *
 * <p>It keeps whatever cookie the server sets and sends it back, the way a browser does, and
 * knows nothing about what is inside it. That is what lets these tests survive Phase 2: when
 * the session cookie becomes a JWT cookie, nothing here changes.
 *
 * <p>Never throws on a 4xx or 5xx - the status is part of the contract under test, so the
 * test asserts on it rather than catching an exception.
 */
public final class ApiClient {

    private final RestClient http;
    private final Map<String, String> cookieJar = new LinkedHashMap<>();

    private ApiClient(RestClient http) {
        this.http = http;
    }

    public static ApiClient onPort(int port) {
        return new ApiClient(RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build());
    }

    public ApiResponse get(String path, Object... uriVariables) {
        return exchange(HttpMethod.GET, path, null, uriVariables);
    }

    public ApiResponse post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    public ApiResponse put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body);
    }

    public ApiResponse patch(String path, Object body) {
        return exchange(HttpMethod.PATCH, path, body);
    }

    public ApiResponse delete(String path, Object... uriVariables) {
        return exchange(HttpMethod.DELETE, path, null, uriVariables);
    }

    /** The cookies this client would send, by name. */
    public Map<String, String> cookies() {
        return Map.copyOf(cookieJar);
    }

    private ApiResponse exchange(HttpMethod method, String path, Object body, Object... uriVariables) {
        RestClient.RequestBodySpec request = http.method(method).uri(path, uriVariables);
        if (!cookieJar.isEmpty()) {
            request = request.header(HttpHeaders.COOKIE, cookieHeader());
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }

        ResponseEntity<String> entity = request.retrieve().toEntity(String.class);
        storeCookies(entity.getHeaders());
        return new ApiResponse(entity.getStatusCode().value(), entity.getHeaders(), entity.getBody());
    }

    private String cookieHeader() {
        return cookieJar.entrySet().stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElseThrow();
    }

    // Only the name and value are kept. Path, Max-Age and the rest are assertions a test
    // makes on the Set-Cookie header itself, not state this client needs.
    private void storeCookies(HttpHeaders responseHeaders) {
        for (String header : responseHeaders.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            // An empty value is how a server deletes a cookie; a browser would drop it here too.
            if (value.isEmpty()) {
                cookieJar.remove(name);
            } else {
                cookieJar.put(name, value);
            }
        }
    }
}
