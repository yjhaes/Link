package com.example.shortlink.api;


import com.example.shortlink.service.error.CreateCacheCoordinationException;
import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.service.error.LinkDisabledException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.LinkStateConflictException;
import com.example.shortlink.service.error.ShortCodeGenerationException;
import com.example.shortlink.service.error.StateCacheCoordinationException;

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

    @ExceptionHandler(com.example.shortlink.stats.query.StatsQueryException.class)
    public ResponseEntity<ApiError> handleStatsQuery(
            com.example.shortlink.stats.query.StatsQueryException exception) {
        return switch (exception.reason()) {
            case BUSY ->
                    error(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "STATS_BUSY",
                            "Statistics query capacity is busy.");
            case TIMEOUT ->
                    error(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "STATS_QUERY_TIMEOUT",
                            "Statistics query timed out.");
            case DATABASE ->
                    error(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_ERROR",
                            "Statistics query failed.");
        };
    }

    @ExceptionHandler(StateCacheCoordinationException.class)
    public ResponseEntity<StateCacheCoordinationError> handleStateCoordinationFailure(
            StateCacheCoordinationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(
                        new StateCacheCoordinationError(
                                "LINK_STATE_CACHE_COORDINATION_UNCONFIRMED",
                                exception.getMessage(),
                                exception.shortCode()));
    }

    @ExceptionHandler(CreateCacheCoordinationException.class)
    public ResponseEntity<CreateCacheCoordinationError> handleCreateCoordinationFailure(
            CreateCacheCoordinationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(
                        new CreateCacheCoordinationError(
                                "CREATE_CACHE_COORDINATION_UNCONFIRMED",
                                exception.getMessage(),
                                exception.shortCode()));
    }

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

    @ExceptionHandler(LinkStateConflictException.class)
    public ResponseEntity<ApiError> handleStateConflict(LinkStateConflictException exception) {
        return error(
                HttpStatus.CONFLICT,
                exception.enabled() ? "LINK_ALREADY_ENABLED" : "LINK_ALREADY_DISABLED",
                exception.enabled()
                        ? "Short link is already enabled."
                        : "Short link is already disabled.");
    }

    @ExceptionHandler({
        InvalidRequestException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        String message =
                exception instanceof InvalidRequestException
                        ? exception.getMessage()
                        : "Request body is invalid.";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ResponseEntity<ApiError> handleShortCodeGenerationFailure(
            ShortCodeGenerationException exception) {
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
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(code, message));
    }
}
