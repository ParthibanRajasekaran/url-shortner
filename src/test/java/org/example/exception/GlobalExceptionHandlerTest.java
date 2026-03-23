package org.example.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRedirectLoop_returns400() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleRedirectLoop(new RedirectLoopException("loop detected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("loop detected");
    }

    @Test
    void handleDataIntegrity_returns409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleDataIntegrity(new DataIntegrityViolationException("duplicate key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("Short code already exists");
    }

    @Test
    void handleResponseStatus_429_returnsCorrectStatus() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(429);
        assertThat(response.getBody().message()).isEqualTo("Rate limit exceeded");
    }

    @Test
    void handleResponseStatus_404_returnsCorrectStatus() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Short code not found");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void handleGeneric_returns500() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleGeneric(new RuntimeException("something exploded"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void errorResponse_containsTimestamp() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleGeneric(new RuntimeException("test"));

        assertThat(response.getBody().timestamp()).isNotNull();
    }
}
