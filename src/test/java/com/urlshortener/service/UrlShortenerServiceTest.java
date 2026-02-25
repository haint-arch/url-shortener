package com.urlshortener.service;

import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.repository.UrlMappingRepository;
import com.urlshortener.util.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for UrlShortenerService.
 * All external dependencies (Cassandra, Redis, Zookeeper) are mocked.
 * This tests pure business logic in isolation.
 */
@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private RangeService rangeService;

    @Spy
    private Base62Encoder base62Encoder = new Base62Encoder();

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UrlShortenerService urlShortenerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlShortenerService, "baseUrl", "http://short.ly");
        ReflectionTestUtils.setField(urlShortenerService, "cacheTtlHours", 24L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ──────────────── POST /shorten tests ────────────────

    @Test
    @DisplayName("shorten: should generate short URL and save to Cassandra + Redis")
    void shortenShouldGenerateShortUrl() throws Exception {
        when(rangeService.getNextId()).thenReturn(1_000_042L);

        ShortenResponse response = urlShortenerService.shorten("https://github.com");

        assertNotNull(response.getShortUrl());
        assertNotNull(response.getCode());
        assertEquals("https://github.com", response.getOriginalUrl());
        assertTrue(response.getShortUrl().startsWith("http://short.ly/"));

        // Verify Cassandra save was called (direct insert, no existence check)
        verify(urlMappingRepository, times(1)).save(any(UrlMapping.class));

        // Verify Redis write-through cache
        verify(valueOperations, times(1)).set(eq("url:" + response.getCode()), eq("https://github.com"), any());
    }

    @Test
    @DisplayName("shorten: code should be base62 encoded from range ID")
    void shortenShouldUseBase62FromRangeId() throws Exception {
        when(rangeService.getNextId()).thenReturn(1_000_000L);

        ShortenResponse response = urlShortenerService.shorten("https://example.com");

        String expectedCode = base62Encoder.encode(1_000_000L);
        assertEquals(expectedCode, response.getCode());
    }

    @Test
    @DisplayName("shorten: each call should produce unique code")
    void shortenShouldProduceUniqueCodes() throws Exception {
        when(rangeService.getNextId()).thenReturn(1_000_000L, 1_000_001L, 1_000_002L);

        ShortenResponse r1 = urlShortenerService.shorten("https://a.com");
        ShortenResponse r2 = urlShortenerService.shorten("https://b.com");
        ShortenResponse r3 = urlShortenerService.shorten("https://c.com");

        assertNotEquals(r1.getCode(), r2.getCode());
        assertNotEquals(r2.getCode(), r3.getCode());
    }

    // ──────────────── GET /{code} tests ────────────────

    @Test
    @DisplayName("resolve: should return from Redis cache on HIT")
    void resolveShouldReturnFromCacheOnHit() {
        when(valueOperations.get("url:abc1234")).thenReturn("https://github.com");

        String result = urlShortenerService.resolve("abc1234");

        assertEquals("https://github.com", result);
        // Cassandra should NOT be called — cache hit
        verify(urlMappingRepository, never()).findByCode(any());
    }

    @Test
    @DisplayName("resolve: should fallback to Cassandra on cache MISS and re-cache")
    void resolveShouldFallbackToCassandraOnMiss() {
        when(valueOperations.get("url:abc1234")).thenReturn(null);
        when(urlMappingRepository.findByCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping(null, "abc1234", "https://github.com", null)));

        String result = urlShortenerService.resolve("abc1234");

        assertEquals("https://github.com", result);
        // Should cache the result after Cassandra read
        verify(valueOperations, times(1)).set(eq("url:abc1234"), eq("https://github.com"), any());
    }

    @Test
    @DisplayName("resolve: should throw UrlNotFoundException when code doesn't exist")
    void resolveShouldThrowWhenNotFound() {
        when(valueOperations.get("url:unknown")).thenReturn(null);
        when(urlMappingRepository.findByCode("unknown")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlShortenerService.resolve("unknown"));
    }

    @Test
    @DisplayName("resolve: should gracefully handle Redis failure and fallback to Cassandra")
    void resolveShouldHandleRedisFailure() {
        when(valueOperations.get("url:abc1234")).thenThrow(new RuntimeException("Redis down"));
        when(urlMappingRepository.findByCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping(null, "abc1234", "https://github.com", null)));

        String result = urlShortenerService.resolve("abc1234");

        assertEquals("https://github.com", result);
    }
}
