package com.ledger.api;

import com.ledger.core.IdempotencyKeyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the core's unchecked exceptions onto status codes. Spring does not
 * map plain {@link IllegalArgumentException} to 400 on its own -- without
 * this, invalid input (self-transfer, a non-positive amount, a blank
 * idempotency key, a malformed account id) would surface as a 500.
 *
 * <p>Deliberately does not catch {@link NullPointerException}: every
 * requireNonNull-style check in core that validates a caller-supplied
 * argument (as opposed to internal wiring, checked in constructors and
 * never caller-reachable) throws IllegalArgumentException instead, so an
 * NPE reaching here means an actual bug, not bad input -- it should
 * surface as a 500, not get silently reported to the caller as a 400.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(IdempotencyKeyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(exception.getMessage()));
    }
}
