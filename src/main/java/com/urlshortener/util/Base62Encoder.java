package com.urlshortener.util;

import org.springframework.stereotype.Component;

/**
 * Converts numeric IDs to base62 short codes and vice versa.
 *
 * Charset: 0-9, a-z, A-Z (62 characters)
 * With 7 characters: 62^7 = 3,521,614,606,208 possible codes (~3.5 trillion)
 */
@Component
public class Base62Encoder {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();
    private static final int CODE_LENGTH = 7;

    public String encode(long number) {
        if (number < 0) {
            throw new IllegalArgumentException("Number must be non-negative");
        }

        StringBuilder sb = new StringBuilder();
        while (number > 0) {
            sb.append(ALPHABET.charAt((int) (number % BASE)));
            number /= BASE;
        }

        // Pad with '0' (first char in ALPHABET) to ensure exactly 7 characters
        while (sb.length() < CODE_LENGTH) {
            sb.append(ALPHABET.charAt(0));
        }

        // Reverse because we built it from least-significant digit
        return sb.reverse().toString();
    }

    public long decode(String code) {
        long number = 0;
        for (char c : code.toCharArray()) {
            int index = ALPHABET.indexOf(c);
            if (index == -1) {
                throw new IllegalArgumentException("Invalid base62 character: " + c);
            }
            number = number * BASE + index;
        }
        return number;
    }
}
