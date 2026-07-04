package com.example.urlshortener.exception;

/** Thrown by the rate-limit filter when a client exceeds its sliding-window quota. */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
