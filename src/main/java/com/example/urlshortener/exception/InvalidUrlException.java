package com.example.urlshortener.exception;

/** Thrown for malformed / non-http(s) input URLs. */
public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
