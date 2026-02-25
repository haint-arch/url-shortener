package com.urlshortener.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test — no Spring context needed.
 * Tests the core base62 encode/decode algorithm.
 */
class Base62EncoderTest {

    private Base62Encoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new Base62Encoder();
    }

    @Test
    @DisplayName("encode should return exactly 7 characters")
    void encodeShouldReturnSevenChars() {
        assertEquals(7, encoder.encode(0).length());
        assertEquals(7, encoder.encode(1).length());
        assertEquals(7, encoder.encode(1_000_000).length());
        assertEquals(7, encoder.encode(999_999_999L).length());
    }

    @Test
    @DisplayName("encode(0) should return '0000000' (all padding)")
    void encodeZeroShouldReturnAllZeros() {
        assertEquals("0000000", encoder.encode(0));
    }

    @Test
    @DisplayName("encode and decode should be inverse operations")
    void encodeDecodeShouldBeInverse() {
        long[] testValues = {0, 1, 61, 62, 1_000, 1_000_000, 1_000_042, 999_999_999L};

        for (long value : testValues) {
            String encoded = encoder.encode(value);
            long decoded = encoder.decode(encoded);
            assertEquals(value, decoded, "encode/decode mismatch for " + value);
        }
    }

    @Test
    @DisplayName("different numbers should produce different codes")
    void differentNumbersShouldProduceDifferentCodes() {
        String code1 = encoder.encode(1_000_000);
        String code2 = encoder.encode(1_000_001);
        String code3 = encoder.encode(2_000_000);

        assertNotEquals(code1, code2);
        assertNotEquals(code1, code3);
        assertNotEquals(code2, code3);
    }

    @Test
    @DisplayName("encode should only contain base62 characters")
    void encodeShouldOnlyContainBase62Chars() {
        String code = encoder.encode(1_000_042);
        assertTrue(code.matches("[0-9a-zA-Z]+"), "Code contains invalid characters: " + code);
    }

    @Test
    @DisplayName("encode should throw on negative input")
    void encodeShouldThrowOnNegativeInput() {
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(-1));
    }

    @Test
    @DisplayName("decode should throw on invalid character")
    void decodeShouldThrowOnInvalidChar() {
        assertThrows(IllegalArgumentException.class, () -> encoder.decode("abc!xyz"));
    }

    @Test
    @DisplayName("max capacity: 62^7 codes should be available")
    void maxCapacityShouldSupportBillionsOfUrls() {
        long maxCodes = (long) Math.pow(62, 7); // 3,521,614,606,208
        assertTrue(maxCodes > 3_000_000_000_000L, "Should support >3 trillion codes");
    }
}
