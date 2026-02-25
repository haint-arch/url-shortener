package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.exception.GlobalExceptionHandler;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer test — only loads UrlController (not the full Spring context).
 * Service is mocked, testing HTTP request/response behavior.
 */
@WebMvcTest(UrlController.class)
@AutoConfigureMockMvc(addFilters = false)  // Disable RateLimitFilter for controller tests
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlShortenerService urlShortenerService;

    @Autowired
    private ObjectMapper objectMapper;

    // ──────────────── POST /shorten ────────────────

    @Test
    @DisplayName("POST /shorten: should return 201 with short URL")
    void shortenShouldReturn201() throws Exception {
        ShortenResponse response = new ShortenResponse("http://short.ly/aB2xK7p", "https://github.com", "aB2xK7p");
        when(urlShortenerService.shorten("https://github.com")).thenReturn(response);

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShortenRequest("https://github.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl").value("http://short.ly/aB2xK7p"))
                .andExpect(jsonPath("$.originalUrl").value("https://github.com"))
                .andExpect(jsonPath("$.code").value("aB2xK7p"));
    }

    @Test
    @DisplayName("POST /shorten: should return 400 for empty URL")
    void shortenShouldReturn400ForEmptyUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /shorten: should return 400 for invalid URL")
    void shortenShouldReturn400ForInvalidUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"not-a-url\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /shorten: should return 400 for missing body")
    void shortenShouldReturn400ForMissingBody() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────── GET /{code} ────────────────

    @Test
    @DisplayName("GET /{code}: should return 301 redirect")
    void redirectShouldReturn301() throws Exception {
        when(urlShortenerService.resolve("aB2xK7p")).thenReturn("https://github.com");

        mockMvc.perform(get("/aB2xK7p"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://github.com"));
    }

    @Test
    @DisplayName("GET /{code}: should return 404 for unknown code")
    void redirectShouldReturn404ForUnknownCode() throws Exception {
        when(urlShortenerService.resolve("unknown")).thenThrow(new UrlNotFoundException("Not found"));

        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }
}
