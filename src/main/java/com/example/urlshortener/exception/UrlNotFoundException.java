package com.example.urlshortener.exception;

/** Thrown when a short code does not exist, is soft-deleted, or has expired. */
public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String code) {
        super("No active URL found for code: " + code);
    }
}
