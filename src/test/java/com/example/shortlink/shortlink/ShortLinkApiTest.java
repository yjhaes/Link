package com.example.shortlink.shortlink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortLinkApiTest {

    private static final String ORIGINAL_URL = "https://example.com/article?id=17#summary";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearMappings() {
        jdbcTemplate.update("DELETE FROM short_link");
    }

    @Test
    void creatingAPermanentLinkReturnsAUsableRedirect() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortCode", matchesPattern("[a-z0-9]{8}")))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andReturn();

        JsonNode response = objectMapper.readTree(creation.getResponse().getContentAsString());
        String code = response.path("shortCode").asText();
        String shortUrl = "http://short.local/s/" + code;

        assertThat(response.path("shortUrl").asText()).isEqualTo(shortUrl);
        assertThat(creation.getResponse().getHeader("Location")).isEqualTo(shortUrl);

        mockMvc.perform(get(URI.create(shortUrl).getRawPath()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void submittingTheSameOriginalUrlTwiceCreatesDifferentMappings() throws Exception {
        String firstCode = createPermanentLink();
        String secondCode = createPermanentLink();

        assertThat(firstCode).isNotEqualTo(secondCode);
    }

    @Test
    void visitingAnUnknownCodeReturnsNotFoundWithoutCachingTheFailure() throws Exception {
        mockMvc.perform(get("/s/missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void visitingAnUnmappedRouteRemainsANotFoundResponse() throws Exception {
        mockMvc.perform(get("/unmapped"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private String createPermanentLink() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("shortCode")
                .asText();
    }
}
