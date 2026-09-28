package com.example.shortlink.common.error;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(LinkNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "Short link not found.");
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ResponseEntity<ApiError> handleExpired(LinkExpiredException exception) {
        return error(HttpStatus.GONE, "LINK_EXPIRED", "Short link has expired.");
    }

    @ExceptionHandler(LinkDisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(LinkDisabledException exception) {
        return error(HttpStatus.FORBIDDEN, "LINK_DISABLED", "Short link is disabled.");
    }

    @ExceptionHandler({InvalidRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        String message = exception instanceof InvalidRequestException
                ? exception.getMessage()
                : "Request body is invalid.";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ResponseEntity<ApiError> handleShortCodeGenerationFailure(ShortCodeGenerationException exception) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SHORT_CODE_GENERATION_FAILED",
                "A unique short code could not be generated. Please try again.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleUnmappedRoute(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(code, message));
    }
}
