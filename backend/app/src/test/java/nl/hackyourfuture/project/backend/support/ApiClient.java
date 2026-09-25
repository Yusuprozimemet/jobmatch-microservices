package nl.hackyourfuture.project.backend.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * An HTTP client for the running application, with a cookie jar.
 *
 * <p>It keeps whatever cookie the server sets and sends it back, the way a browser does, and
 * knows nothing about what is inside it. That is what lets these tests survive Phase 2: when
 * the session cookie becomes a JWT cookie, nothing here changes.
 *
 * <p>Never throws on a 4xx or 5xx - the status is part of the contract under test, so the
 * test asserts on it rather than catching an exception. It does not follow redirects either:
 * the Google sign-in flow answers with a 302 whose {@code Location} <em>is</em> the contract,
 * and following it would leave the test chasing the frontend, which is not running.
 *
 * <p>A routed client sends each request to the service that owns its path. The cookie jar
 * belongs to the client, not the host, so a login made on the monolith is sent to the other
 * service too.
 */
public final class ApiClient {

    // Pinned rather than left to RestClient's detection, because a request factory that
    // follows redirects would swallow the 302s the OAuth2 tests assert on.
    private static final JdkClientHttpRequestFactory REQUEST_FACTORY = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());

    private final String baseUrl;
    private final RestClient http;
    private final Function<String, String> route;
    private final Map<String, String> cookieJar = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    private ApiClient(String baseUrl, RestClient http, Function<String, String> route) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.route = route;
    }

    /** Where this client's requests go. */
    public String baseUrl() {
        return baseUrl;
    }

    public static ApiClient onPort(int port) {
        return at("http://localhost:" + port);
    }

    /** A client for this base URL: the application's own, or the gateway's in front of it. */
    public static ApiClient at(String baseUrl) {
        return routed(baseUrl, path -> baseUrl);
    }

    /**
     * A client that sends each request to the base URL this function gives for its path, the
     * variables expanded and the query left out; {@code baseUrl} for the rest.
     */
    public static ApiClient routed(String baseUrl, Function<String, String> baseUrlForPath) {
        return new ApiClient(baseUrl, RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(REQUEST_FACTORY)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build(), baseUrlForPath);
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

    /**
     * Puts a cookie in the jar as if a server had set it earlier: a browser still holding one
     * that has since expired, or one somebody edited.
     */
    public ApiClient withCookie(String name, String value) {
        cookieJar.put(name, value);
        return this;
    }

    /**
     * Sends this header on every later request, as a client may send anything: one the
     * application must not trust, for example.
     */
    public ApiClient withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** The cookies this client would send, by name. */
    public Map<String, String> cookies() {
        return Map.copyOf(cookieJar);
    }

    private ApiResponse exchange(HttpMethod method, String path, Object body, Object... uriVariables) {
        String target = route.apply(UriComponentsBuilder.fromUriString(path).buildAndExpand(uriVariables).getPath());
        // An absolute URI is sent as it is, whatever the client's base URL.
        RestClient.RequestBodySpec request = target.equals(baseUrl)
                ? http.method(method).uri(path, uriVariables)
                : http.method(method).uri(target + path, uriVariables);

        if (!cookieJar.isEmpty()) {
            request = request.header(HttpHeaders.COOKIE, cookieHeader());
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
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

    // Only the name and value are kept. Path and the rest are assertions a test makes on the
    // Set-Cookie header itself, not state this client needs.
    private void storeCookies(HttpHeaders responseHeaders) {
        for (String header : responseHeaders.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            // A browser drops a cookie the server deleted, however the deletion was spelled.
            if (Cookies.deletes(header)) {
                cookieJar.remove(name);
            } else {
                cookieJar.put(name, value);
            }
        }
    }
}
