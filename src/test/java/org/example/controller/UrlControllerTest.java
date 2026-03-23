package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.ShortenRequest;
import org.example.dto.ShortenResponse;
import org.example.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UrlService urlService;

    @Test
    void testPost_validUrl_returns201() throws Exception {
        ShortenResponse response = new ShortenResponse(
                "abc1234", "https://swiftlink.io/abc1234", "https://example.com", Instant.now()
        );
        when(urlService.shortenUrl(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShortenRequest("https://example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc1234"));
    }

    @Test
    void testPost_invalidUrl_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\": \"not-a-url\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void testPost_blankUrl_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\": \"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void testGet_validCode_returns301WithLocation() throws Exception {
        when(urlService.resolveUrl("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void testGet_notFound_returns404() throws Exception {
        when(urlService.resolveUrl("zzzzzzz"))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Short code not found"));

        mockMvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound());
    }
}
