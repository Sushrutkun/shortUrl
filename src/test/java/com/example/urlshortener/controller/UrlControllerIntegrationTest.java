package com.example.urlshortener.controller;

import com.example.urlshortener.service.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"url.click-events"})
class UrlControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Cache is mocked so this test suite doesn't need a real Redis instance; every
    // lookup behaves like a cache miss, exercising the DB fallback path deliberately.
    @MockBean
    private CacheService cacheService;

    // RateLimitFilter and CacheService both depend on StringRedisTemplate directly;
    // deep-stub so opsForZSet()/opsForValue() calls don't NPE without a real Redis server.
    @MockBean(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Test
    void fullLifecycle_createRedirectStatsUpdateDelete() throws Exception {
        when(cacheService.get(anyString())).thenReturn(null);

        Map<String, Object> createBody = new HashMap<>();
        createBody.put("url", "https://example.com/very/long/path");

        String createResponse = mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(createBody)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").isNotEmpty())
            .andExpect(jsonPath("$.originalUrl").value("https://example.com/very/long/path"))
            .andReturn().getResponse().getContentAsString();

        String code = objectMapper.readTree(createResponse).get("code").asText();

        // Redirect: expect 302, not 301 (caching would break click accuracy).
        mockMvc.perform(get("/" + code))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.com/very/long/path"));

        // Stats: reachable immediately (click count itself may lag - see "approximate" flag).
        mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.approximate").value(true));

        // Update.
        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("url", "https://example.com/updated/path");
        mockMvc.perform(put("/api/v1/urls/" + code)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(updateBody)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.originalUrl").value("https://example.com/updated/path"));

        verify(cacheService, atLeastOnce()).evict(code);

        // Delete (soft) then confirm the code no longer resolves.
        mockMvc.perform(delete("/api/v1/urls/" + code))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/" + code))
            .andExpect(status().isNotFound());
    }

    @Test
    void shorten_rejectsNonHttpUrl() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "ftp://not-allowed.com");

        mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shorten_withCustomAliasThatIsTaken_returns409() throws Exception {
        when(cacheService.get(anyString())).thenReturn(null);

        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://example.com/one");
        body.put("alias", "myalias123");

        mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated());

        Map<String, Object> bodyDuplicate = new HashMap<>();
        bodyDuplicate.put("url", "https://example.com/two");
        bodyDuplicate.put("alias", "myalias123");

        mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(bodyDuplicate)))
            .andExpect(status().isConflict());
    }

    @Test
    void shorten_sameLongUrlTwice_returnsSameCode() throws Exception {
        when(cacheService.get(anyString())).thenReturn(null);

        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://example.com/dedup-target");

        String first = mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String code1 = objectMapper.readTree(first).get("code").asText();
        String code2 = objectMapper.readTree(second).get("code").asText();

        org.assertj.core.api.Assertions.assertThat(code1).isEqualTo(code2);
    }

    @Test
    void redirect_unknownCode_returns404() throws Exception {
        when(cacheService.get(anyString())).thenReturn(null);
        mockMvc.perform(get("/does-not-exist"))
            .andExpect(status().isNotFound());
    }
}
