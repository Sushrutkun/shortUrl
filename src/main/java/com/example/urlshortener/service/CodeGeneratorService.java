package com.example.urlshortener.service;

import com.example.urlshortener.exception.CodeGenerationException;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Generates collision-safe 8-char base62 codes.
 *
 * Deliberately NOT a sequential auto-increment: a plain counter (0,1,2,3...) is trivially
 * enumerable, letting anyone scrape every URL in the system. Instead each attempt hashes
 * (originalUrl + a fresh random UUID) with SHA-256 and takes the first 8 base62 characters.
 * A fresh UUID per retry (not just re-hashing the same input) is what makes retries actually
 * produce a different candidate.
 */
@Service
public class CodeGeneratorService {

    private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 3;

    private final ShortCodeBloomFilter bloomFilter;

    public CodeGeneratorService(ShortCodeBloomFilter bloomFilter) {
        this.bloomFilter = bloomFilter;
    }

    /**
     * @param originalUrl   the URL being shortened (mixed into the hash input)
     * @param existsInDb    callback: definitive DB existence check, used only when the bloom
     *                      filter reports a possible match (never trust the filter alone)
     * @return a code guaranteed (as of the check) not to collide with an active mapping
     */
    public String generate(String originalUrl, Predicate<String> existsInDb) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = hashToCode(originalUrl + ":" + UUID.randomUUID());

            boolean maybeTaken = bloomFilter.mightContain(candidate);
            if (!maybeTaken) {
                // Bloom filter says "definitely not taken" - fast path, skip the DB round trip.
                return candidate;
            }
            // Bloom filter said "maybe taken" - this could be a false positive, so confirm with DB.
            if (!existsInDb.test(candidate)) {
                return candidate;
            }
            // Genuinely taken - loop and retry with a brand-new UUID.
        }
        throw new CodeGenerationException(
            "Failed to generate a unique short code after " + MAX_ATTEMPTS + " attempts");
    }

    private String hashToCode(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            BigInteger bigInt = new BigInteger(1, hashBytes);

            StringBuilder sb = new StringBuilder();
            BigInteger base = BigInteger.valueOf(62);
            while (bigInt.compareTo(BigInteger.ZERO) > 0 && sb.length() < CODE_LENGTH) {
                BigInteger[] divMod = bigInt.divideAndRemainder(base);
                sb.append(BASE62.charAt(divMod[1].intValue()));
                bigInt = divMod[0];
            }
            while (sb.length() < CODE_LENGTH) {
                sb.append('0'); // pad if the hash prefix happened to be small
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** SHA-256 hex digest of the raw URL - used as the indexed dedup key (see ShortUrl.urlHash). */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
