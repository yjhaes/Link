package com.example.shortlink.shortlink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
@Import(ShortLinkApiTest.ClockConfiguration.class)
class ShortLinkApiTest {

    private static final String ORIGINAL_URL = "https://example.com/article?id=17#summary";
    private static final Instant BASE_TIME = Instant.parse("2026-09-28T12:00:00.987654321Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void clearMappings() {
        jdbcTemplate.update("DELETE FROM short_link");
        clock.reset(BASE_TIME);
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND expires_at IS NULL", Integer.class, code))
                .isEqualTo(1);

        mockMvc.perform(get(URI.create(shortUrl).getRawPath()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void creatingAnExpiringLinkStoresAndReturnsOneUtcInstantAtDatabasePrecision() throws Exception {
        Instant createdAt = BASE_TIME.truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = createdAt.plus(Duration.ofMinutes(15));
        clock.advanceAfterEachRead(Duration.ofSeconds(1));

        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary","validMinutes":15}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()))
                .andReturn();

        assertThat(clock.readCount()).isEqualTo(1);
        JsonNode response = objectMapper.readTree(creation.getResponse().getContentAsString());
        String code = response.path("shortCode").asText();
        LocalDateTime storedCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM short_link WHERE short_code = ?", LocalDateTime.class, code);
        LocalDateTime storedExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM short_link WHERE short_code = ?", LocalDateTime.class, code);

        assertThat(storedCreatedAt).isEqualTo(LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        assertThat(storedExpiresAt).isEqualTo(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        assertThat(response.path("expiresAt").asText()).endsWith("Z");
    }

    @Test
    void aLinkRedirectsBeforeExpiryAndReturnsGoneAtAndAfterExpiry() throws Exception {
        String code = createExpiringLink(1);
        Instant expiresAt = BASE_TIME.truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofMinutes(1));

        clock.setInstant(expiresAt.minusMillis(1));
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));

        clock.setInstant(expiresAt);
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));

        clock.setInstant(expiresAt.plusMillis(1));
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
    }

    @Test
    void omittedAndNullValidMinutesBothCreatePermanentLinks() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary","validMinutes":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andReturn();

        String code = objectMapper.readTree(creation.getResponse().getContentAsString())
                .path("shortCode")
                .asText();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND expires_at IS NULL", Integer.class, code))
                .isEqualTo(1);
    }

    @Test
    void theMaximumValidDurationIsAccepted() throws Exception {
        Instant expectedExpiry = BASE_TIME.truncatedTo(ChronoUnit.MILLIS)
                .plus(Duration.ofMinutes(5_256_000));

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary","validMinutes":5256000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expectedExpiry.toString()));
    }

    @Test
    void invalidValidMinutesReturnTheStandardInvalidRequestBody() throws Exception {
        for (String invalidValue : new String[]{"0", "-1", "1.5", "5256001"}) {
            mockMvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"originalUrl":"https://example.com/article?id=17#summary","validMinutes":%s}
                                    """.formatted(invalidValue)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
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

    private String createExpiringLink(int validMinutes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("originalUrl", ORIGINAL_URL)
                                .put("validMinutes", validMinutes)
                                .toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("shortCode")
                .asText();
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        ControllableClock controllableClock() {
            return new ControllableClock(BASE_TIME, ZoneOffset.UTC);
        }
    }

    static final class ControllableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final AtomicReference<Duration> advanceAfterRead;
        private final AtomicLong readCount;
        private final ZoneId zone;

        ControllableClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), new AtomicReference<>(Duration.ZERO), new AtomicLong(), zone);
        }

        private ControllableClock(
                AtomicReference<Instant> instant,
                AtomicReference<Duration> advanceAfterRead,
                AtomicLong readCount,
                ZoneId zone) {
            this.instant = instant;
            this.advanceAfterRead = advanceAfterRead;
            this.readCount = readCount;
            this.zone = zone;
        }

        void reset(Instant value) {
            instant.set(value);
            advanceAfterRead.set(Duration.ZERO);
            readCount.set(0);
        }

        void setInstant(Instant value) {
            instant.set(value);
        }

        void advanceAfterEachRead(Duration duration) {
            advanceAfterRead.set(duration);
        }

        long readCount() {
            return readCount.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this.zone.equals(zone)
                    ? this
                    : new ControllableClock(instant, advanceAfterRead, readCount, zone);
        }

        @Override
        public Instant instant() {
            readCount.incrementAndGet();
            return instant.getAndUpdate(value -> value.plus(advanceAfterRead.get()));
        }
    }
}
