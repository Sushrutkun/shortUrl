package com.example.urlshortener.exception;

/** Thrown when a requested custom alias (or, after retries, a generated code) is already taken. */
public class AliasConflictException extends RuntimeException {
    public AliasConflictException(String alias) {
        super("Alias or code already exists: " + alias);
    }
}
