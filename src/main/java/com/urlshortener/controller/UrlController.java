package com.urlshortener.controller;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.service.UrlShortenerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class UrlController {

    private final UrlShortenerService urlShortenerService;

    /**
     * POST /shorten
     * Creates a short URL from a long URL.
     *
     * Request:  { "url": "https://example.com/very/long/path" }
     * Response: { "shortUrl": "http://short.ly/aB2xK7p", "originalUrl": "...", "code": "aB2xK7p" }
     */
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        ShortenResponse response = urlShortenerService.shorten(request.getUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /{code}
     * Redirects to the original URL using HTTP 301 (Moved Permanently).
     *
     * 301 = browser caches the redirect → subsequent visits don't hit our server.
     * Trade-off: less control over analytics. Switch to 302 if tracking is needed.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String originalUrl = urlShortenerService.resolve(code);

        return ResponseEntity
                .status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
    }
}
