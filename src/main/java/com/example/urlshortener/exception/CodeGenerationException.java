package com.example.urlshortener.exception;

/** Thrown when code generation exhausts its retry budget (extremely unlikely at 8-char base62). */
public class CodeGenerationException extends RuntimeException {
    public CodeGenerationException(String message) {
        super(message);
    }
}
