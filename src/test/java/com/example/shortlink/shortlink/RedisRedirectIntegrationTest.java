package com.example.shortlink.shortlink;

import com.example.shortlink.LinkApplication;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LinkApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RedisRedirectIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "shortlink:redirect:v1:";
    private static final String ORIGINAL_URL = "https://redis-cache.example/article";
    private static final long CACHE_TTL_MILLIS = 300_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("short_link_test")
            .withUsername("short_link")
            .withPassword("short_link_test");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private ShortLinkMapper shortLinkMapper;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @BeforeEach
    void clearTestData() {
        jdbcTemplate.update("DELETE FROM short_link");
        jdbcTemplate.update("DELETE FROM short_code_issuance");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void creatingAPermanentMappingDoesNotPrewarmTheRedirectCache() throws Exception {
        String response = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String shortCode = objectMapper.readTree(response).path("shortCode").asText();

        assertThat(redisTemplate.hasKey(cacheKey(shortCode))).isFalse();
    }

    @Test
    void firstRedirectMissLoadsMySqlAndWritesTheExpectedValueWithAnExpiringTtl() throws Exception {
        String shortCode = "A1b2";
        insertMapping(shortCode, ORIGINAL_URL);

        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));

        String serializedValue = redisTemplate.opsForValue().get(cacheKey(shortCode));
        JsonNode cacheValue = objectMapper.readTree(serializedValue);
        assertThat(cacheValue.size()).isEqualTo(2);
        assertThat(cacheValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        assertThat(cacheValue.has("expiresAt")).isTrue();
        assertThat(cacheValue.get("expiresAt").isNull()).isTrue();

        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween(CACHE_TTL_MILLIS - 10_000L, CACHE_TTL_MILLIS);
        verify(shortLinkMapper).selectById(shortCode);
    }

    @Test
    void repeatedRedirectsUseCaseSensitiveKeysAndDoNotReadMySqlAgain() throws Exception {
        insertMapping("A1b2", "https://uppercase.example/");
        insertMapping("a1b2", "https://lowercase.example/");

        assertRedirect("A1b2", "https://uppercase.example/");
        assertRedirect("a1b2", "https://lowercase.example/");
        clearInvocations(shortLinkMapper);

        assertRedirect("A1b2", "https://uppercase.example/");
        assertRedirect("a1b2", "https://lowercase.example/");

        verify(shortLinkMapper, never()).selectById("A1b2");
        verify(shortLinkMapper, never()).selectById("a1b2");
        assertThat(redisTemplate.opsForValue().get(cacheKey("A1b2")))
                .contains("https://uppercase.example/");
        assertThat(redisTemplate.opsForValue().get(cacheKey("a1b2")))
                .contains("https://lowercase.example/");
    }

    @Test
    void expiringRedirectStoresItsBusinessExpiryAndRemainingTtlAndLaterHitsAvoidMySql() throws Exception {
        String shortCode = "Tm12";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(1)
                .truncatedTo(ChronoUnit.MILLIS);
        insertExpiringMapping(shortCode, ORIGINAL_URL, expiresAt);

        assertRedirect(shortCode, ORIGINAL_URL);

        String key = cacheKey(shortCode);
        JsonNode cacheValue = objectMapper.readTree(redisTemplate.opsForValue().get(key));
        Instant expectedExpiry = expiresAt.toInstant(ZoneOffset.UTC);
        assertThat(cacheValue.size()).isEqualTo(2);
        assertThat(cacheValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        assertThat(cacheValue.path("expiresAt").asText()).isEqualTo(expectedExpiry.toString());

        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        long remainingMillis = Duration.between(Instant.now(), expectedExpiry).toMillis();
        assertThat(ttlMillis).isPositive();
        assertThat(ttlMillis).isLessThanOrEqualTo(CACHE_TTL_MILLIS);
        assertThat(ttlMillis).isBetween(remainingMillis - 10_000L, remainingMillis + 1_000L);
        verify(shortLinkMapper).selectById(shortCode);

        clearInvocations(shortLinkMapper);
        assertRedirect(shortCode, ORIGINAL_URL);
        verify(shortLinkMapper, never()).selectById(shortCode);
    }

    @Test
    void expiringRedirectTtlDoesNotExceedTheConfiguredMaximum() throws Exception {
        String shortCode = "Cp12";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(10)
                .truncatedTo(ChronoUnit.MILLIS);
        insertExpiringMapping(shortCode, ORIGINAL_URL, expiresAt);

        assertRedirect(shortCode, ORIGINAL_URL);

        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween(CACHE_TTL_MILLIS - 10_000L, CACHE_TTL_MILLIS);
    }

    @Test
    void expiredCacheHitReturnsGoneAndDeletesTheKeyWithoutReadingMySql() throws Exception {
        String shortCode = "Ex12";
        Instant expiresAt = Instant.now().minusSeconds(1);
        String value = objectMapper.createObjectNode()
                .put("originalUrl", ORIGINAL_URL)
                .put("expiresAt", expiresAt.toString())
                .toString();
        redisTemplate.opsForValue().set(cacheKey(shortCode), value, Duration.ofMinutes(5));
        clearInvocations(shortLinkMapper);

        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(redisTemplate.hasKey(cacheKey(shortCode))).isFalse();
        verify(shortLinkMapper, never()).selectById(shortCode);
    }

    @Test
    void malformedCacheValueFallsBackToMySqlAndReplacesTheEntry() throws Exception {
        String shortCode = "Bad1";
        insertMapping(shortCode, ORIGINAL_URL);
        redisTemplate.opsForValue().set(cacheKey(shortCode), "{broken", Duration.ofMinutes(5));

        assertRedirect(shortCode, ORIGINAL_URL);

        JsonNode repairedValue = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(repairedValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        verify(shortLinkMapper, times(1)).selectById(shortCode);
    }

    private void assertRedirect(String shortCode, String originalUrl) throws Exception {
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", originalUrl))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void insertMapping(String shortCode, String originalUrl) {
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, original_url, created_at, expires_at, enabled) " +
                        "VALUES (?, ?, ?, NULL, TRUE)",
                shortCode,
                originalUrl,
                LocalDateTime.now(ZoneOffset.UTC));
    }

    private void insertExpiringMapping(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, original_url, created_at, expires_at, enabled) " +
                        "VALUES (?, ?, ?, ?, TRUE)",
                shortCode,
                originalUrl,
                LocalDateTime.now(ZoneOffset.UTC),
                expiresAt);
    }

    private String cacheKey(String shortCode) {
        return CACHE_KEY_PREFIX + shortCode;
    }
}
