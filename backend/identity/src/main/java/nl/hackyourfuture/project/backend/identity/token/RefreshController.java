package nl.hackyourfuture.project.backend.identity.token;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/auth/refresh}: a live refresh cookie buys a new access token and a new refresh
 * token, and is spent doing it (Day 13). No access token is needed; it is usually the one that
 * has expired.
 */
@RestController
class RefreshController {

    private final AuthCookies authCookies;

    RefreshController(AuthCookies authCookies) {
        this.authCookies = authCookies;
    }

    @PostMapping("/api/auth/refresh")
    @Operation(summary = "Refresh the tokens", description = "Trades the refresh cookie for new access and refresh cookies.")
    @ApiResponse(responseCode = "200", description = "New cookies set")
    @ApiResponse(responseCode = "401", description = "No live refresh token; both cookies deleted")
    ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        return authCookies.refresh(request, response)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
