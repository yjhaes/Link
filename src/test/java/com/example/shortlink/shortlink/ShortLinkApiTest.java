package com.example.shortlink.shortlink;

import com.example.shortlink.LinkApplication;
import com.example.shortlink.shortlink.service.CreatedShortLink;
import com.example.shortlink.shortlink.service.PermutedShortCodeEncoder;
import com.example.shortlink.shortlink.service.RedirectCache;
import com.example.shortlink.shortlink.service.ShortLinkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;

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
@Import(ShortLinkApiTest.ControlledTimeConfiguration.class)
class ShortLinkApiTest {

    private static final String ORIGINAL_URL = "https://example.com/article?id=17#summary";
    private static final Instant BASE_TIME = Instant.parse("2026-09-28T12:00:00.987654321Z");
    private static final int CONCURRENT_CREATION_COUNT = 16;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ControllableClock clock;

    @Autowired
    private ControllablePermutedShortCodeEncoder shortCodeEncoder;

    @BeforeEach
    void clearMappings() {
        jdbcTemplate.update("DELETE FROM short_link");
        clock.reset(BASE_TIME);
        shortCodeEncoder.reset();
    }

    @Test
    void creatingAPermanentLinkReturnsAUsableRedirect() throws Exception {
        long issuedCountBefore = issuedCount();
        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shortCode", matchesPattern("[0-9a-zA-Z]{4,8}")))
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
        assertThat(issuedCount()).isEqualTo(issuedCountBefore + 1);

        mockMvc.perform(get(URI.create(shortUrl).getRawPath()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void creationRetriesAfterAnExistingShortCodeAndReturnsTheSuccessfulCandidate() throws Exception {
        String occupiedCode = "aaaa0001";
        insertMapping(occupiedCode, "https://occupied.example/");
        shortCodeEncoder.forceCode(occupiedCode, 1);
        long issuedCountBefore = issuedCount();

        MvcResult creation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/article?id=17#summary"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String successfulCode = objectMapper.readTree(creation.getResponse().getContentAsByteArray())
                .path("shortCode")
                .asText();
        assertThat(objectMapper.readTree(creation.getResponse().getContentAsByteArray())
                .path("shortUrl").asText()).isEqualTo("http://short.local/s/" + successfulCode);
        assertThat(creation.getResponse().getHeader("Location"))
                .isEqualTo("http://short.local/s/" + successfulCode);
        assertThat(issuedCount()).isEqualTo(issuedCountBefore + 2);
        assertThat(successfulCode).isEqualTo(shortCodeEncoder.encode(latestIssuedId()));
        assertThat(successfulCode).isNotEqualTo(occupiedCode);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, successfulCode)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(2);
    }

    @Test
    void creationStopsAfterTwoPrimaryKeyCollisionsWithoutCreatingAMapping() throws Exception {
        String occupiedCode = "aaaa0001";
        insertMapping(occupiedCode, "https://occupied.example/" + occupiedCode);
        shortCodeEncoder.forceCode(occupiedCode, 2);
        long issuedCountBefore = issuedCount();

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

        assertThat(issuedCount()).isEqualTo(issuedCountBefore + 2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void aDifferentUniqueConstraintFailureLeavesItsIssuedIdUnusedAndDoesNotRetry() throws Exception {
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX uq_test_original_url_prefix ON short_link (original_url(16))");
        long issuedCountBefore = issuedCount();
        try {
            insertMapping("aaaa0001", ORIGINAL_URL);

            mockMvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"originalUrl":"https://example.com/article?id=17#summary"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                    .andExpect(header().string("Cache-Control", "no-store"));

            assertThat(issuedCount()).isEqualTo(issuedCountBefore + 1);
            long failedId = latestIssuedId();
            String unusedCode = shortCodeEncoder.encode(failedId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, unusedCode)).isZero();
        } finally {
            jdbcTemplate.execute("DROP INDEX uq_test_original_url_prefix ON short_link");
        }

        long failedId = latestIssuedId();
        MvcResult successfulCreation = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://after-failure.example/path"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long successfulId = latestIssuedId();
        String failedCode = shortCodeEncoder.encode(failedId);
        String successfulCode = shortCodeEncoder.encode(successfulId);

        assertThat(successfulId).isGreaterThan(failedId);
        assertThat(objectMapper.readTree(successfulCreation.getResponse().getContentAsByteArray())
                .path("shortCode").asText()).isEqualTo(successfulCode);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, failedCode)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, successfulCode)).isEqualTo(1);
    }

    @Test
    void concurrentCreationsOnTheSharedMySqlPrimaryUseDistinctShortCodes() throws Exception {
        long issuedCountBefore = issuedCount();
        List<Callable<MvcResult>> requests = IntStream.range(0, CONCURRENT_CREATION_COUNT)
                .<Callable<MvcResult>>mapToObj(index -> () -> mockMvc.perform(post("/api/links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.createObjectNode()
                                        .put("originalUrl", "https://example.com/concurrent/" + index)
                                        .toString()))
                        .andReturn())
                .toList();
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Future<MvcResult>> results = executor.invokeAll(requests);
            Set<String> shortCodes = new HashSet<>();
            for (Future<MvcResult> result : results) {
                MvcResult creation = result.get();
                assertThat(creation.getResponse().getStatus()).isEqualTo(201);
                String code = objectMapper.readTree(creation.getResponse().getContentAsByteArray())
                        .path("shortCode")
                        .asText();
                assertThat(code).matches("[0-9a-zA-Z]{4,8}");
                shortCodes.add(code);
            }

            assertThat(shortCodes).hasSize(CONCURRENT_CREATION_COUNT);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class))
                    .isEqualTo(CONCURRENT_CREATION_COUNT);
            assertThat(issuedCount()).isEqualTo(issuedCountBefore + CONCURRENT_CREATION_COUNT);
        } finally {
            executor.shutdownNow();
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
        assertNotFound("missing1");
    }

    @Test
    void invalidShortCodeLengthsAndCharactersReturnNotFoundWithoutCachingFailures() throws Exception {
        for (String code : List.of("abc", "abcdefghi", "ab_c", "ab-c")) {
            assertNotFound(code);
        }
    }

    @Test
    void separateApplicationContextsShareMySqlIssuedIdsWithoutDuplicateShortCodes() throws Exception {
        long issuedCountBefore = issuedCount();

        try (ConfigurableApplicationContext firstInstance = startSeparateApplicationInstance();
             ConfigurableApplicationContext secondInstance = startSeparateApplicationInstance()) {
            ShortLinkService firstService = firstInstance.getBean(ShortLinkService.class);
            ShortLinkService secondService = secondInstance.getBean(ShortLinkService.class);
            List<Callable<CreatedShortLink>> requests = IntStream.range(0, CONCURRENT_CREATION_COUNT)
                    .<Callable<CreatedShortLink>>mapToObj(index -> () -> {
                        ShortLinkService service = index % 2 == 0 ? firstService : secondService;
                        return service.create("https://example.com/multi-instance/" + index, null);
                    })
                    .toList();
            ExecutorService executor = Executors.newFixedThreadPool(8);

            try {
                List<Future<CreatedShortLink>> results = executor.invokeAll(requests);
                Set<String> shortCodes = new HashSet<>();
                for (Future<CreatedShortLink> result : results) {
                    String shortCode = result.get().shortCode();
                    assertThat(shortCode).matches("[0-9a-zA-Z]{4,8}");
                    shortCodes.add(shortCode);
                }

                assertThat(shortCodes).hasSize(CONCURRENT_CREATION_COUNT);
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class))
                        .isEqualTo(CONCURRENT_CREATION_COUNT);
                assertThat(issuedCount()).isEqualTo(issuedCountBefore + CONCURRENT_CREATION_COUNT);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private ConfigurableApplicationContext startSeparateApplicationInstance() {
        return new SpringApplicationBuilder(LinkApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--spring.sql.init.mode=never", "--spring.main.banner-mode=off");
    }

    @Test
    void shortCodeColumnIsVariableLengthCaseSensitiveAndRequired() {
        assertShortCodeColumnIsVariableLengthCaseSensitiveAndRequired();
    }

    @Test
    void sqlIssuedIdsUseASeparateAutoIncrementTable() {
        List<String> mappingColumns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'short_link' " +
                        "ORDER BY ORDINAL_POSITION",
                String.class);
        var issuedIdColumn = jdbcTemplate.queryForMap(
                "SELECT DATA_TYPE, COLUMN_TYPE, COLUMN_KEY, EXTRA FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'short_code_issuance' " +
                        "AND COLUMN_NAME = 'id'");

        assertThat(mappingColumns).containsExactly(
                "short_code", "original_url", "created_at", "expires_at", "enabled");
        assertThat(issuedIdColumn.get("DATA_TYPE").toString()).isEqualToIgnoringCase("bigint");
        assertThat(issuedIdColumn.get("COLUMN_TYPE").toString()).isEqualToIgnoringCase("bigint");
        assertThat(issuedIdColumn.get("COLUMN_KEY")).isEqualTo("PRI");
        assertThat(issuedIdColumn.get("EXTRA").toString()).containsIgnoringCase("auto_increment");
    }

    @Test
    void schemaInitializationMigratesAnExistingFixedLengthShortCodeColumn() {
        jdbcTemplate.execute(
                "ALTER TABLE short_link MODIFY COLUMN short_code " +
                        "CHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")),
                dataSource);

        assertShortCodeColumnIsVariableLengthCaseSensitiveAndRequired();
    }

    private void assertShortCodeColumnIsVariableLengthCaseSensitiveAndRequired() {
        var shortCodeColumn = jdbcTemplate.queryForMap(
                "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLLATION_NAME " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'short_link' " +
                        "AND COLUMN_NAME = 'short_code'");

        assertThat(shortCodeColumn.get("DATA_TYPE").toString()).isEqualToIgnoringCase("varchar");
        assertThat(((Number) shortCodeColumn.get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(8);
        assertThat(shortCodeColumn.get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(shortCodeColumn.get("COLLATION_NAME")).isEqualTo("utf8mb4_bin");
    }

    private void assertNotFound(String code) throws Exception {
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void savedFourToEightCharacterBase62CodesRedirectWithCaseSensitiveLookup() throws Exception {
        insertMapping("A1b2", "https://four-uppercase.example/");
        insertMapping("a1b2", "https://four-lowercase.example/");
        insertMapping("Ab3dE9f", "https://seven.example/");
        insertMapping("A1b2Cd3E", "https://eight.example/");

        assertRedirectsTo("A1b2", "https://four-uppercase.example/");
        assertRedirectsTo("a1b2", "https://four-lowercase.example/");
        assertRedirectsTo("Ab3dE9f", "https://seven.example/");
        assertRedirectsTo("A1b2Cd3E", "https://eight.example/");
    }

    private void assertRedirectsTo(String shortCode, String originalUrl) throws Exception {
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", originalUrl))
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

    private long issuedCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_code_issuance", Long.class);
    }

    private long latestIssuedId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM short_code_issuance ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private void assertDisabled(String code) throws Exception {
        mockMvc.perform(get("/s/" + code))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @TestConfiguration
    static class ControlledTimeConfiguration {

        @Bean
        @Primary
        RedirectCache redirectCache() {
            return new RedirectCache() {
                @Override
                public Optional<String> findPermanent(String shortCode) {
                    return Optional.empty();
                }

                @Override
                public void storePermanent(String shortCode, String originalUrl) {
                }
            };
        }

        @Bean
        @Primary
        ControllableClock controllableClock() {
            return new ControllableClock(BASE_TIME, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ControllablePermutedShortCodeEncoder controllablePermutedShortCodeEncoder() {
            return new ControllablePermutedShortCodeEncoder();
        }
    }

    static final class ControllablePermutedShortCodeEncoder extends PermutedShortCodeEncoder {

        private String forcedCode;
        private int remainingForcedEncodes;

        synchronized void reset() {
            forcedCode = null;
            remainingForcedEncodes = 0;
        }

        synchronized void forceCode(String code, int times) {
            forcedCode = code;
            remainingForcedEncodes = times;
        }

        @Override
        public synchronized String encode(long id) {
            if (remainingForcedEncodes > 0) {
                remainingForcedEncodes--;
                return forcedCode;
            }
            return super.encode(id);
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
