package com.urlshortener.service;

import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.repository.UrlMappingRepository;
import com.urlshortener.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenerService {

    private final RangeService rangeService;
    private final Base62Encoder base62Encoder;
    private final UrlMappingRepository urlMappingRepository;
    private final StringRedisTemplate redisTemplate;

    @Value("${shortener.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${shortener.cache.ttl-hours:24}")
    private long cacheTtlHours;

    /**
     * CREATION FLOW:
     * 1. Get next unique ID from RangeService (local counter, no DB call)
     * 2. Convert to base62 short code
     * 3. Direct INSERT into Cassandra (no existence check needed — range guarantees uniqueness)
     * 4. Write-through: cache in Redis immediately (newly created URLs are likely clicked soon)
     * 5. Return short URL to user
     */
    public ShortenResponse shorten(String originalUrl) {
        try {
            long id = rangeService.getNextId();

            String code = base62Encoder.encode(id);

            UrlMapping mapping = new UrlMapping(
                    UUID.randomUUID(), // In production, use UUID v7 library
                    code,
                    originalUrl,
                    Instant.now()
            );
            urlMappingRepository.save(mapping);

            // Write-through cache: pre-warm Redis so first click is fast
            cacheUrl(code, originalUrl);

            String shortUrl = baseUrl + "/" + code;
            log.info("Shortened: {} -> {}", originalUrl, shortUrl);

            return new ShortenResponse(shortUrl, originalUrl, code);

        } catch (Exception e) {
            log.error("Failed to shorten URL: {}", originalUrl, e);
            throw new RuntimeException("Failed to create short URL", e);
        }
    }

    /**
     * REDIRECT FLOW (Cache-Aside with Write-Through on miss):
     * 1. Check Redis cache first (~1ms latency)
     * 2. HIT  → return original URL immediately (99% of cases)
     * 3. MISS → query Cassandra, cache the result, then return
     * 4. NOT FOUND → throw exception → 404
     *
     * If Redis is down, gracefully falls back to Cassandra only.
     */
    public String resolve(String code) {
        // Step 1: Try Redis cache first
        String cachedUrl = getFromCache(code);
        if (cachedUrl != null) {
            log.debug("Cache HIT for code: {}", code);
            return cachedUrl;
        }

        log.debug("Cache MISS for code: {}, querying Cassandra", code);

        // Step 2: Cache miss — query Cassandra
        UrlMapping mapping = urlMappingRepository.findByCode(code)
                .orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + code));

        // Step 3: Cache the result for future requests
        cacheUrl(code, mapping.getOriginalUrl());

        return mapping.getOriginalUrl();
    }

    private void cacheUrl(String code, String originalUrl) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey(code),
                    originalUrl,
                    Duration.ofHours(cacheTtlHours)
            );
        } catch (Exception e) {
            // Redis failure should NOT break the service — graceful degradation
            log.warn("Failed to cache URL in Redis: {}", e.getMessage());
        }
    }

    private String getFromCache(String code) {
        try {
            return redisTemplate.opsForValue().get(cacheKey(code));
        } catch (Exception e) {
            // Redis down → fall back to Cassandra, don't crash
            log.warn("Redis read failed, falling back to Cassandra: {}", e.getMessage());
            return null;
        }
    }

    private String cacheKey(String code) {
        return "url:" + code;
    }
}
