package com.example.shortlink;

import com.example.shortlink.LinkApplication;
import com.example.shortlink.service.RedirectCache;
import com.example.shortlink.service.RedirectCacheEntry;
import com.example.shortlink.service.RedirectCacheRead;
import com.example.shortlink.service.CreatedShortLink;
import com.example.shortlink.service.ShortLinkService;
import com.example.shortlink.service.PermutedShortCodeEncoder;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockingDetails;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
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

    private static final String CACHE_KEY_PREFIX = "shortlink:redirect:v2:";
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

    @MockitoSpyBean
    private RedirectCache redirectCache;

    @MockitoSpyBean
    private PermutedShortCodeEncoder shortCodeEncoder;

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(placeholder.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(placeholder.path("originalUrl").isNull()).isTrue();
        assertThat(redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);
    }

    @Test
    void committedCreationWithUnconfirmedCoordinationReturnsRecoverable503() throws Exception {
        doThrow(new IllegalStateException("Redis coordination timed out"))
                .when(redirectCache).replaceVersion(anyString());

        String response = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CREATE_CACHE_COORDINATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.message").value(
                        "Database creation committed; cache coordination unconfirmed."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).path("shortCode").asText();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, shortCode)).isEqualTo(1);
        long mappings = mappingCount();
        long issued = issuanceCount();
        jdbcTemplate.update("UPDATE short_link SET enabled = FALSE WHERE short_code = ?", shortCode);
        doCallRealMethod().when(redirectCache).replaceVersion(anyString());
        shortLinkService.recoverCacheCoordination(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);

        assertThat(mappingCount()).isEqualTo(mappings);
        assertThat(issuanceCount()).isEqualTo(issued);
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"));
    }

    @Test
    void successfulCreationClearsAPrevious404AndReturnsAUsableMapping() throws Exception {
        String shortCode = "Made";
        mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
        String previousGeneration = redirectCache.find(shortCode).generation();
        doReturn(shortCode).when(shortCodeEncoder).encode(anyLong());

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(shortCode));
        assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(previousGeneration);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        assertRedirect(shortCode, ORIGINAL_URL);
    }

    @Test
    void creationConfirmsItsOwnCommitEvenWhenACallingTransactionRollsBack() throws Exception {
        String shortCode = new TransactionTemplate(transactionManager).execute(transaction -> {
            CreatedShortLink created = shortLinkService.create(ORIGINAL_URL, null);
            transaction.setRollbackOnly();
            return created.shortCode();
        });

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(mappingCount()).isEqualTo(1);
        assertThat(issuanceCount()).isEqualTo(1);
    }

    @Test
    void unconfirmedDatabaseInsertReturns500WithoutPartialCompletionOrCoordination() throws Exception {
        doThrow(new IllegalStateException("Database commit was not confirmed"))
                .when(shortLinkMapper).insert(any(ShortLinkEntity.class));

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.shortCode").doesNotExist());
        verify(redirectCache, never()).replaceVersion(anyString());
    }

    @Test
    void executedCoordinationWithLostAcknowledgementCanBeRecoveredRepeatedly() throws Exception {
        String shortCode = "New1";
        doReturn(shortCode).when(shortCodeEncoder).encode(anyLong());
        mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
        String oldGeneration = redirectCache.find(shortCode).generation();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Redis applied the write but its response timed out");
        }).when(redirectCache).replaceVersion(shortCode);

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.shortCode").value(shortCode));
        assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(oldGeneration);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);

        doCallRealMethod().when(redirectCache).replaceVersion(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);
        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(mappingCount()).isEqualTo(1);
        assertThat(issuanceCount()).isEqualTo(1);
    }

    @Test
    void creationInAnIndependentInstanceRejectsLate404RefillAndNewRequestsSeeTheMapping() throws Exception {
        String shortCode = "Race";
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch resumeOldQuery = new CountDownLatch(1);
        AtomicBoolean firstQuery = new AtomicBoolean(true);
        AtomicReference<Boolean> oldRefillAccepted = new AtomicReference<>();
        var queryMySql = mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object snapshot = queryMySql.answer(invocation);
            if (firstQuery.getAndSet(false)) {
                assertThat(snapshot).isNull();
                snapshotRead.countDown();
                if (!resumeOldQuery.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the old query");
                }
            }
            return snapshot;
        }).when(shortLinkMapper).selectById(shortCode);
        doAnswer(invocation -> {
            Boolean accepted = (Boolean) invocation.callRealMethod();
            oldRefillAccepted.set(accepted);
            return accepted;
        }).when(redirectCache).storeIfVersion(eq(shortCode), anyString(),
                eq(RedirectCacheRead.Status.NOT_FOUND), isNull());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ConfigurableApplicationContext creator = independentCreator()) {
            assertThat(creator.getBean(RedirectCache.class)).isNotSameAs(redirectCache);
            Future<Integer> oldRequest = executor.submit(() ->
                    mockMvc.perform(get("/s/" + shortCode)).andReturn().getResponse().getStatus());
            assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
            String oldGeneration = redirectCache.find(shortCode).generation();

            CreatedShortLink created = creator.getBean(ShortLinkService.class).create(ORIGINAL_URL, null);
            assertThat(created.shortCode()).isEqualTo(shortCode);
            assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(oldGeneration);
            // This request starts after create returned, while the old snapshot is still paused.
            assertRedirect(shortCode, ORIGINAL_URL);
            resumeOldQuery.countDown();
            // Overlapping requests may finish with the snapshot they read before creation.
            assertThat(oldRequest.get(5, TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(oldRefillAccepted.get()).isFalse();
            assertRedirect(shortCode, ORIGINAL_URL);
            assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.REDIRECT);
        } finally {
            resumeOldQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseQueryFailureReturns500AndDoesNotCacheNotFound() throws Exception {
        doThrow(new IllegalStateException("MySQL query failed")).when(shortLinkMapper).selectById("Fail");
        mockMvc.perform(get("/s/Fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(redirectCache.find("Fail").status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        doAnswer(mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer())
                .when(shortLinkMapper).selectById("Fail");
        mockMvc.perform(get("/s/Fail")).andExpect(status().isNotFound());
        assertThat(redirectCache.find("Fail").status()).isEqualTo(RedirectCacheRead.Status.NOT_FOUND);
    }

    @Test
    void newInvalidCodesEachQueryMySqlAndHaveFiniteTtlAndExpirationReloads() throws Exception {
        for (String shortCode : new String[]{"Bad1", "Bad2", "Bad3"}) {
            mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
            verify(shortLinkMapper).selectById(shortCode);
            assertThat(redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS))
                    .isPositive().isLessThanOrEqualTo(30_000L);
        }
        redisTemplate.expire(cacheKey("Bad1"), Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey("Bad1"))));
        mockMvc.perform(get("/s/Bad1")).andExpect(status().isNotFound());
        verify(shortLinkMapper, times(2)).selectById("Bad1");
    }

    @Test
    void repeatedPostAfterALostResponseStillCreatesAnotherMapping() throws Exception {
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                    .andExpect(status().isCreated());
        }
        assertThat(mappingCount()).isEqualTo(2);
        assertThat(issuanceCount()).isEqualTo(2);
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
        assertThat(cacheValue.size()).isEqualTo(5);
        assertThat(cacheValue.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(cacheValue.path("generation").asText()).isNotBlank();
        assertThat(cacheValue.path("status").asText()).isEqualTo("REDIRECT");
        assertThat(cacheValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        assertThat(cacheValue.has("expiresAt")).isTrue();
        assertThat(cacheValue.get("expiresAt").isNull()).isTrue();

        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween((long) (CACHE_TTL_MILLIS * 0.9) - 1_000L, CACHE_TTL_MILLIS);
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
    void committedDisableAndReenableTakeEffectAfterDeletingThePrewarmedKey() throws Exception {
        String shortCode = "Md12";
        String key = cacheKey(shortCode);
        insertMapping(shortCode, ORIGINAL_URL);

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(redisTemplate.hasKey(key)).isTrue();

        assertThat(jdbcTemplate.update(
                "UPDATE short_link SET enabled = FALSE WHERE short_code = ?", shortCode))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM short_link WHERE short_code = ?", Integer.class, shortCode))
                .isZero();
        assertThat(redisTemplate.hasKey(key)).isTrue();
        assertThat(redisTemplate.delete(key)).isTrue();
        assertThat(redisTemplate.hasKey(key)).isFalse();

        clearInvocations(shortLinkMapper);
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(shortLinkMapper).selectById(shortCode);
        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(key));
        assertThat(placeholder.path("status").asText()).isEqualTo("PLACEHOLDER");

        assertThat(jdbcTemplate.update(
                "UPDATE short_link SET enabled = TRUE WHERE short_code = ?", shortCode))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM short_link WHERE short_code = ?", Integer.class, shortCode))
                .isEqualTo(1);
        redisTemplate.delete(key);

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(redisTemplate.hasKey(key)).isTrue();
        verify(shortLinkMapper, times(2)).selectById(shortCode);
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
        assertThat(cacheValue.size()).isEqualTo(5);
        assertThat(cacheValue.path("status").asText()).isEqualTo("REDIRECT");
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
        assertThat(ttlMillis).isBetween((long) (CACHE_TTL_MILLIS * 0.9) - 1_000L, CACHE_TTL_MILLIS);
    }

    @Test
    void expiredCacheHitReturnsGoneAndDeletesTheKeyWithoutReadingMySql() throws Exception {
        String shortCode = "Ex12";
        Instant expiresAt = Instant.now().minusSeconds(1);
        String value = objectMapper.createObjectNode()
                .put("schemaVersion", 2)
                .put("generation", "00000000-0000-0000-0000-000000000001")
                .put("status", "REDIRECT")
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

    @Test
    void firstReadAtomicallyInitializesAFinitePlaceholderAndLaterReadsItsGeneration() throws Exception {
        String shortCode = "Mis1";

        RedirectCacheRead firstRead = redirectCache.find(shortCode);
        String serializedPlaceholder = redisTemplate.opsForValue().get(cacheKey(shortCode));
        JsonNode placeholder = objectMapper.readTree(serializedPlaceholder);
        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);

        assertThat(firstRead.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(firstRead.generation()).isNotBlank();
        assertThat(placeholder.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(placeholder.path("generation").asText()).isEqualTo(firstRead.generation());
        assertThat(placeholder.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(placeholder.path("originalUrl").isNull()).isTrue();
        assertThat(placeholder.path("expiresAt").isNull()).isTrue();
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);

        RedirectCacheRead secondRead = redirectCache.find(shortCode);
        assertThat(secondRead.status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        assertThat(secondRead.generation()).isEqualTo(firstRead.generation());
    }

    @Test
    void aValueWithoutFiniteRedisTtlIsReplacedWithANewVersionedPlaceholder() throws Exception {
        String shortCode = "Inf1";
        String oldGeneration = "00000000-0000-0000-0000-000000000001";
        redisTemplate.opsForValue().set(
                cacheKey(shortCode),
                objectMapper.createObjectNode()
                        .put("schemaVersion", 2)
                        .put("generation", oldGeneration)
                        .put("status", "REDIRECT")
                        .put("originalUrl", ORIGINAL_URL)
                        .putNull("expiresAt")
                        .toString());

        RedirectCacheRead read = redirectCache.find(shortCode);
        JsonNode replacement = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);

        assertThat(read.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(read.generation()).isNotEqualTo(oldGeneration);
        assertThat(replacement.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(replacement.path("generation").asText()).isEqualTo(read.generation());
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);
    }

    @Test
    void redirectCacheProtocolKeepsTheThreeRejectionResultsDistinct() throws Exception {
        RedirectCacheRead.Status[] outcomes = {
                RedirectCacheRead.Status.NOT_FOUND,
                RedirectCacheRead.Status.EXPIRED,
                RedirectCacheRead.Status.DISABLED
        };

        for (int index = 0; index < outcomes.length; index++) {
            String shortCode = "Rj" + index + "1";
            RedirectCacheRead initialized = redirectCache.find(shortCode);
            RedirectCacheEntry result = outcomes[index] == RedirectCacheRead.Status.DISABLED
                    ? new RedirectCacheEntry(null, Instant.now().plusSeconds(60))
                    : null;

            assertThat(redirectCache.storeIfVersion(shortCode, initialized.generation(), outcomes[index], result))
                    .isTrue();
            RedirectCacheRead cached = redirectCache.find(shortCode);

            assertThat(cached.status()).isEqualTo(outcomes[index]);
            if (outcomes[index] == RedirectCacheRead.Status.DISABLED) {
                assertThat(cached.entry().expiresAt()).isEqualTo(result.expiresAt());
            }
        }
    }

    @Test
    void repeatedMissingMappingRequestsReuseNotFoundWithoutRenewingTtl() throws Exception {
        String shortCode = "Nope";
        Long firstTtl = null;
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/s/" + shortCode))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            Long ttl = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(26_000L, 30_000L);
            if (firstTtl == null) {
                firstTtl = ttl;
            } else {
                assertThat(ttl).isLessThanOrEqualTo(firstTtl);
            }
        }

        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(placeholder.path("status").asText()).isEqualTo("NOT_FOUND");
        verify(shortLinkMapper).selectById(shortCode);
    }

    @Test
    void versionReplacementAndMissingEntryBothRejectAnOldConditionalRefill() throws Exception {
        String replacedCode = "Rep1";
        RedirectCacheRead beforeReplacement = redirectCache.find(replacedCode);
        String replacementGeneration = redirectCache.replaceVersion(replacedCode);

        assertThat(replacementGeneration).isNotEqualTo(beforeReplacement.generation());
        assertThat(redirectCache.storeIfVersion(
                replacedCode,
                beforeReplacement.generation(),
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(ORIGINAL_URL, null))).isFalse();
        assertThat(redirectCache.find(replacedCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        JsonNode replacement = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(replacedCode)));
        assertThat(replacement.path("generation").asText()).isEqualTo(replacementGeneration);
        assertThat(replacement.path("status").asText()).isEqualTo("PLACEHOLDER");

        String lostCode = "Los1";
        RedirectCacheRead beforeLoss = redirectCache.find(lostCode);
        assertThat(redisTemplate.delete(cacheKey(lostCode))).isTrue();
        RedirectCacheRead afterLoss = redirectCache.find(lostCode);

        assertThat(afterLoss.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(afterLoss.generation()).isNotEqualTo(beforeLoss.generation());
        assertThat(redirectCache.storeIfVersion(
                lostCode,
                beforeLoss.generation(),
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(ORIGINAL_URL, null))).isFalse();
        JsonNode freshEntry = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(lostCode)));
        assertThat(freshEntry.path("generation").asText()).isEqualTo(afterLoss.generation());
        assertThat(freshEntry.path("status").asText()).isEqualTo("PLACEHOLDER");
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

    private long mappingCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Long.class);
    }

    private long issuanceCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_code_issuance", Long.class);
    }

    private ConfigurableApplicationContext independentCreator() {
        return new SpringApplicationBuilder(LinkApplication.class, FixedCodeConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                        "--spring.sql.init.mode=never", "--spring.main.banner-mode=off");
    }

    @TestConfiguration
    static class FixedCodeConfiguration {
        @Bean
        @Primary
        PermutedShortCodeEncoder fixedEncoder() {
            return new PermutedShortCodeEncoder() {
                @Override
                public String encode(long id) {
                    return "Race";
                }
            };
        }
    }
}
