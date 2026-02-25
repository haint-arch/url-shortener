package com.urlshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Rate limiting filter using Redis as a shared counter across all app servers.
 *
 * Algorithm: Fixed Window Counter
 * - Key: "rl:{ip}" with TTL = window size (e.g. 60s)
 * - Each request increments the counter (INCR is atomic in Redis)
 * - If counter > max allowed → reject with 429
 * - When key expires → counter resets automatically
 *
 * Why Redis instead of local counter?
 * With multiple app servers behind a load balancer, each server would
 * count independently. Attacker sends 10 req to each of 10 servers = 100 total.
 * Redis provides a single shared counter across all servers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${shortener.rate-limit.max-requests:10}")
    private long maxRequests;

    @Value("${shortener.rate-limit.window-seconds:60}")
    private long windowSeconds;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Only rate limit POST /shorten (creation is the expensive operation)
        // GET /{code} redirects are cheap (cached) and should not be limited
        if (!request.getMethod().equals("POST")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        String key = "rl:" + clientIp;

        try {
            Long count = redisTemplate.opsForValue().increment(key);

            // First request for this window → set expiry
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }

            if (count != null && count > maxRequests) {
                log.warn("Rate limit exceeded for IP: {} (count: {})", clientIp, count);
                rejectRequest(response, clientIp);
                return;
            }

            // Add rate limit headers so client knows their usage
            response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, maxRequests - count)));

        } catch (Exception e) {
            // Redis down → allow request (fail-open, same principle as caching)
            log.warn("Rate limit check failed, allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract real client IP, considering reverse proxy / load balancer.
     * X-Forwarded-For header contains the original client IP when behind a proxy.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void rejectRequest(HttpServletResponse response, String ip) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));

        Map<String, Object> body = Map.of(
                "error", "Too Many Requests",
                "message", "Rate limit exceeded. Max " + maxRequests + " requests per " + windowSeconds + "s",
                "timestamp", Instant.now().toString()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
