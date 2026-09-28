package com.example.shortlink.shortlink;

import com.example.shortlink.shortlink.service.ShortCodeGenerator;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
@Import(ShortLinkApiTest.ControlledTimeAndShortCodeConfiguration.class)
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

    @Autowired
    private ControllableShortCodeGenerator shortCodeGenerator;

    @BeforeEach
    void clearMappings() {
        jdbcTemplate.update("DELETE FROM short_link");
        clock.reset(BASE_TIME);
        shortCodeGenerator.reset();
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
    void creationRetriesAfterAnExistingShortCodeAndReturnsTheSuccessfulCandidate() throws Exception {
        insertMapping("aaaa0001", "https://occupied.example/");
        shortCodeGenerator.enqueueCandidates("aaaa0001", "bbbb0002");

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("bbbb0002"))
                .andExpect(jsonPath("$.shortUrl").value("http://short.local/s/bbbb0002"))
                .andExpect(header().string("Location", "http://short.local/s/bbbb0002"));

        assertThat(shortCodeGenerator.callCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = 'bbbb0002'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(2);
    }

    @Test
    void creationStopsAfterFourPrimaryKeyCollisionsWithoutCreatingAMapping() throws Exception {
        List<String> occupiedCodes = List.of("aaaa0001", "bbbb0002", "cccc0003", "dddd0004");
        for (String code : occupiedCodes) {
            insertMapping(code, "https://occupied.example/" + code);
        }
        shortCodeGenerator.enqueueCandidates(occupiedCodes.toArray(String[]::new));

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SHORT_CODE_GENERATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "A unique short code could not be generated. Please try again."))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(shortCodeGenerator.callCount()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(4);
    }

    @Test
    void aDifferentUniqueConstraintFailureIsNotRetriedOrExposed() throws Exception {
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX uq_test_original_url_prefix ON short_link (original_url(16))");
        try {
            insertMapping("aaaa0001", ORIGINAL_URL);
            shortCodeGenerator.enqueueCandidates("bbbb0002", "cccc0003");

            mockMvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"originalUrl":"https://example.com/article?id=17#summary"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                    .andExpect(header().string("Cache-Control", "no-store"));

            assertThat(shortCodeGenerator.callCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP INDEX uq_test_original_url_prefix ON short_link");
        }
    }

    @Test
    void validLocalAndUnreachableUrisAreStoredAndRedirectedExactlyAsSubmitted() throws Exception {
        List<String> originalUrls = List.of(
                "http://localhost:8080/a%2Fb?x=one&x=two#section",
                "https://192.168.1.14/internal/page?next=%2Fhome#top",
                "http://does-not-exist.invalid:1/unreachable?raw=%7e#fragment");

        for (String originalUrl : originalUrls) {
            createAndAssertOriginalUrlRoundTrip(originalUrl);
        }
    }

    @Test
    void acceptsTheMaximumUrlLengthAndRejectsOneCharacterMore() throws Exception {
        String prefix = "https://localhost/";
        String maximumUrl = prefix + "a".repeat(4096 - prefix.length());
        assertThat(maximumUrl).hasSize(4096);

        createAndAssertOriginalUrlRoundTrip(maximumUrl);

        String tooLongUrl = maximumUrl + "a";
        assertInvalidRequest(objectMapper.createObjectNode().put("originalUrl", tooLongUrl).toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidOriginalUrlsReturnTheStandardUncachedInvalidRequest() throws Exception {
        List<String> invalidUrls = List.of(
                " https://example.com/path",
                "https://example.com/path ",
                "https://exa mple.com/path",
                "https://example.com/a\tb",
                "https://example.com/%ZZ",
                "https://example.com:65536/path",
                "https://example.com:999999999999999999999/path",
                "https:///path",
                "http:/example.com/path",
                "ftp://example.com/path",
                "https://user:password@example.com/path",
                "https://example.com/路径",
                "https://example.com/a" + (char) 1 + "b");

        for (String originalUrl : invalidUrls) {
            assertInvalidRequest(objectMapper.createObjectNode()
                    .put("originalUrl", originalUrl)
                    .toString());
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isZero();
    }

    @Test
    void missingUrlAndMalformedJsonReturnTheStandardUncachedInvalidRequest() throws Exception {
        for (String body : new String[]{"{}", "{\"originalUrl\":null}", "{\"originalUrl\":\"\"}", "{"}) {
            assertInvalidRequest(body);
        }
        assertInvalidRequest("");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isZero();
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
        String code = createLink(1);
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
    void disablingAnUnexpiredLinkReturnsForbiddenWithoutCachingTheFailure() throws Exception {
        String code = createLink(15);

        setEnabled(code, false);
        assertDisabled(code);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND enabled = FALSE",
                Integer.class,
                code))
                .isEqualTo(1);
    }

    @Test
    void reEnablingAnUnexpiredLinkRestoresItsExistingShortCode() throws Exception {
        String code = createLink(15);

        setEnabled(code, false);
        assertDisabled(code);
        setEnabled(code, true);

        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND enabled = TRUE",
                Integer.class,
                code))
                .isEqualTo(1);
    }

    @Test
    void expiryTakesPriorityForDisabledLinksAndReEnablingCannotRestoreThem() throws Exception {
        String code = createLink(1);
        Instant expiresAt = BASE_TIME.truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofMinutes(1));

        setEnabled(code, false);
        clock.setInstant(expiresAt);

        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        setEnabled(code, true);

        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND enabled = TRUE",
                Integer.class,
                code))
                .isEqualTo(1);
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
        for (String invalidValue : new String[]{"0", "-1", "1.5", "1.0", "1e0", "5256001"}) {
            String request = """
                    {"originalUrl":"https://example.com/article?id=17#summary","validMinutes":%s}
                    """.formatted(invalidValue);
            assertInvalidRequest(request);
        }
    }

    @Test
    void submittingTheSameOriginalUrlTwiceCreatesDifferentMappings() throws Exception {
        String firstCode = createLink(null);
        String secondCode = createLink(null);

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

    private String createLink(Integer validMinutes) throws Exception {
        var request = objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL);
        if (validMinutes != null) {
            request.put("validMinutes", validMinutes);
        }
        MvcResult result = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("shortCode")
                .asText();
    }

    private void createAndAssertOriginalUrlRoundTrip(String originalUrl) throws Exception {
        String request = objectMapper.createObjectNode()
                .put("originalUrl", originalUrl)
                .toString();
        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String code = objectMapper.readTree(creation.getResponse().getContentAsByteArray())
                .path("shortCode")
                .asText();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT original_url FROM short_link WHERE short_code = ?", String.class, code))
                .isEqualTo(originalUrl);
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", originalUrl))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void assertInvalidRequest(String body) throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void setEnabled(String code, boolean enabled) {
        assertThat(jdbcTemplate.update(
                "UPDATE short_link SET enabled = ? WHERE short_code = ?", enabled, code))
                .isEqualTo(1);
    }

    private void insertMapping(String code, String originalUrl) {
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, original_url, created_at, expires_at, enabled) " +
                        "VALUES (?, ?, ?, NULL, TRUE)",
                code,
                originalUrl,
                LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC));
    }

    private void assertDisabled(String code) throws Exception {
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @TestConfiguration
    static class ControlledTimeAndShortCodeConfiguration {

        @Bean
        @Primary
        ControllableClock controllableClock() {
            return new ControllableClock(BASE_TIME, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ControllableShortCodeGenerator controllableShortCodeGenerator() {
            return new ControllableShortCodeGenerator();
        }
    }

    static final class ControllableShortCodeGenerator extends ShortCodeGenerator {

        private final Queue<String> candidates = new ArrayDeque<>();
        private int calls;

        synchronized void reset() {
            candidates.clear();
            calls = 0;
        }

        synchronized void enqueueCandidates(String... shortCodes) {
            candidates.addAll(List.of(shortCodes));
        }

        synchronized int callCount() {
            return calls;
        }

        @Override
        public synchronized String generate() {
            calls++;
            return candidates.isEmpty() ? super.generate() : candidates.remove();
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
